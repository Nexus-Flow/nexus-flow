package net.nexus_flow.benchmarks;

import java.io.Serial;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxConfig;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Notification-based {@code awaitOutboxIdle} latency benchmark. Validates that the worker's
 * Condition-based idle signal wakes the caller within microseconds of actual quiescence —
 * NOT within {@code workerPollInterval} as a polling-based implementation would.
 *
 * <p>The expected shape: regardless of {@code workerPollInterval}, {@code awaitOutboxIdle}
 * latency tracks the per-row dispatch cost + a Condition signal hop (≤ 100 µs typically).
 * A regression to polling would surface as a per-call latency floor equal to half of
 * {@code workerPollInterval}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class AwaitOutboxIdleBenchmark {

    public record Bump() {
    }

    public static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        public Bumped(String aggId) {
            super(aggId);
        }
    }

    public static final class Counter extends Aggregate {
        public void bump() {
            recordEvent(new Bumped("agg-bench"));
        }
    }

    private FlowRuntime    runtime;
    private Command<Bump>  command;

    @Setup
    public void setup() {
        InMemoryOutboxStorage outboxStorage = new InMemoryOutboxStorage();
        OutboxConfig          outboxConfig  = OutboxConfig.builder(
                                                                  outboxStorage, new JavaSerializationOutboxPayloadCodec())
                .useOutboxFanOut(true)
                // Deliberately slow poll interval (250 ms) so a regression to polling-based
                // awaitIdle would surface as a 125 ms floor instead of the sub-ms latency the
                // Condition-based implementation guarantees.
                .workerPollInterval(Duration.ofMillis(250))
                .build();

        runtime = FlowRuntime.builder()
                .outbox(outboxConfig)
                .build();

        runtime.commands().register(new AbstractNoReturnCommandHandler<Bump>() {
            @Override
            protected void handle(Bump cmd) {
                Counter c = new Counter();
                c.bump();
            }
        });
        command = Command.<Bump>builder().body(new Bump()).build();
    }

    @TearDown
    public void tearDown() {
        runtime.close();
    }

    @Benchmark
    public void dispatchAndAwaitIdle(Blackhole bh) {
        runtime.commands().dispatch(command);
        bh.consume(runtime.awaitOutboxIdle(Duration.ofSeconds(2)));
    }
}

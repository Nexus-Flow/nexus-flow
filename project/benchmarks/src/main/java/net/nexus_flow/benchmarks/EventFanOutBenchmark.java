package net.nexus_flow.benchmarks;


import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.Serial;
import java.util.concurrent.TimeUnit;

/**
 * Full event fan-out benchmark through {@code DefaultEventBus#dispatchResult}.
 * Exercises the dispatch pipeline end-to-end:
 *
 * <ul>
 *   <li>{@code ClassValue}-cached plan lookup.</li>
 *   <li>Sequential ordered iteration of the plan list.</li>
 *   <li>{@link java.lang.invoke.MethodHandle}-backed invokers per listener.</li>
 *   <li>Cached {@link net.nexus_flow.core.types.TypeReference#hashCode()}.</li>
 * </ul>
 *
 * <p>Each listener does negligible work (touches a counter through a
 * {@link Blackhole}-friendly sink) so the benchmark surfaces the
 * dispatch-pipeline overhead, not handler bodies.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class EventFanOutBenchmark {

    @Param({"1", "10", "100"})
    public int listenerCount;

    private FlowRuntime runtime;
    private FanEvent event;

    public static final class FanEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        public FanEvent(String aggregateId) { super(aggregateId); }
    }

    /** Cheapest possible listener so the dispatch overhead dominates. */
    public static final class NoopListener extends AbstractDomainEventListener<FanEvent> {
        public int count;
        @Override public void handle(FanEvent event) {
            count++;
        }
    }

    @Setup
    public void setup() {
        runtime = FlowRuntime.builder().build();
        for (int i = 0; i < listenerCount; i++) {
            runtime.events().register(new NoopListener());
        }
        event = new FanEvent("agg-bench");
    }

    @TearDown
    public void tearDown() {
        runtime.close();
    }

    @Benchmark
    public void dispatchResult_failFast(Blackhole bh) {
        bh.consume(runtime.events().dispatchResult(
                event, ExecutionContext.root(), ErrorPolicy.failFast()));
    }

    @Benchmark
    public void dispatch_legacyFireAndForget(Blackhole bh) {
        // Exercises the fire-and-forget entry point: pure synchronous
        // fan-out, no DispatchResult build cost.
        runtime.events().dispatch(event, /*isSaga=*/false);
        bh.consume(event);
    }
}


package net.nexus_flow.benchmarks;

import java.io.Serial;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
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
 * Full durable dispatch benchmark: command → handler → outbox append → worker claim → listener
 * → worker-side recursive drain → next outbox row. Exercises the entire {@code
 * useOutboxFanOut(true)} path that the framework supports for exactly-once local delivery with
 * a cascade.
 *
 * <p>The handler emits one event; the listener mutates the same aggregate to emit a second
 * event. The worker-side drain catches the second event and appends it for the next poll. The
 * benchmark waits for {@code FlowRuntime#awaitOutboxIdle} to confirm the full cascade has
 * propagated.
 *
 * <p>Measures end-to-end p50 latency for a 2-event cascade under outbox-only delivery — the
 * production shape for any service that emits events from listeners and needs single-delivery
 * semantics without an external InboxStorage.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class OutboxFullDurableDispatchBenchmark {

    public record PlaceCmd(String id, int qty) {
    }

    public static final class Placed extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        public Placed(String aggId) {
            super(aggId);
        }
    }

    public static final class Reserved extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        public Reserved(String aggId) {
            super(aggId);
        }
    }

    public static final class Order extends Aggregate {
        public void markReserved() {
            recordEvent(new Reserved("agg-bench-" + COUNTER.incrementAndGet()));
        }
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();

    public static final class StockListener extends AbstractDomainEventListener<Placed> {
        private final java.util.concurrent.ConcurrentHashMap<String, Order> orders;

        public StockListener(java.util.concurrent.ConcurrentHashMap<String, Order> orders) {
            this.orders = orders;
        }

        @Override
        public void handle(Placed event) {
            Order o = orders.get(event.getAggregateId());
            if (o != null) {
                o.markReserved();
            }
        }
    }

    public static final class CompletedListener extends AbstractDomainEventListener<Reserved> {
        public int count;

        @Override
        public void handle(Reserved event) {
            count++;
        }
    }

    private FlowRuntime              runtime;
    private Command<PlaceCmd>        command;
    private java.util.concurrent.ConcurrentHashMap<String, Order> orders;
    private CompletedListener        completed;

    @Setup
    public void setup() {
        orders    = new java.util.concurrent.ConcurrentHashMap<>();
        completed = new CompletedListener();

        InMemoryOutboxStorage outboxStorage = new InMemoryOutboxStorage();
        OutboxConfig          outboxConfig  = OutboxConfig.builder(
                                                                  outboxStorage, new JavaSerializationOutboxPayloadCodec())
                .useOutboxFanOut(true)
                .workerPollInterval(Duration.ofMillis(5))
                .build();

        runtime = FlowRuntime.builder()
                .outbox(outboxConfig)
                .build();

        runtime.commands().register(new AbstractNoReturnCommandHandler<PlaceCmd>() {
            @Override
            protected void handle(PlaceCmd cmd) {
                Order o = new Order();
                orders.put("agg-bench-0", o);
                o.recordEvent(new Placed("agg-bench-0"));
            }
        });
        runtime.events().register(new StockListener(orders));
        runtime.events().register(completed);

        command = Command.<PlaceCmd>builder().body(new PlaceCmd("bench", 1)).build();
    }

    @TearDown
    public void tearDown() {
        runtime.close();
    }

    @Benchmark
    public void fullCascadeDispatch(Blackhole bh) {
        runtime.commands().dispatch(command);
        // Block until the worker has drained the FULL cascade (Placed → drain → Reserved →
        // drain → idle). Notification-based: the worker signals quiescence on a JDK
        // Condition.
        boolean idle = runtime.awaitOutboxIdle(Duration.ofSeconds(2));
        bh.consume(idle);
        bh.consume(completed.count);
    }
}

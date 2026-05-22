package net.nexus_flow.benchmarks;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Aggregate hot-path benchmark: {@code recordEvent}, {@code replay}, {@code drainCommitted}.
 * Measures the cost of the per-aggregate lifecycle guarantees:
 *
 * <ul>
 * <li>{@code lifecycleMonitor.lock()/unlock()} on every {@code recordEvent}.
 * <li>Sequence number stamp + monotonic counter advance.
 * <li>{@code apply(event)} hook invocation (no-op here so the framework cost dominates).
 * <li>{@code DomainEventContext.recordEvent} forward to the ambient sink.
 * <li>{@code replay(events)} on a fresh instance — the event-sourcing rehydration path.
 * </ul>
 *
 * <p>{@link #eventsPerAggregate} sweeps 1 (single-event aggregate, common for thin domains) up to
 * 1 000 (long-lived aggregate with many state transitions, e.g. an order saga) so callers can
 * extrapolate the per-event cost from the curve.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class AggregateRecordReplayBenchmark {

    public static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        public Bumped(String aggId) {
            super(aggId);
        }
    }

    /** No-op aggregate; recordEvent stays on the framework's hot path. */
    public static final class BenchmarkAggregate extends Aggregate {
        public BenchmarkAggregate() {
            super();
        }
    }

    @Param({"1", "10", "100", "1000"})
    public int eventsPerAggregate;

    private List<DomainEvent> preBuiltEvents;

    @Setup
    public void setup() {
        preBuiltEvents = new ArrayList<>(eventsPerAggregate);
        for (int i = 0; i < eventsPerAggregate; i++) {
            preBuiltEvents.add(new Bumped("agg-bench"));
        }
    }

    @Benchmark
    public void recordEventLoop(Blackhole bh) {
        BenchmarkAggregate agg = new BenchmarkAggregate();
        for (DomainEvent event : preBuiltEvents) {
            agg.recordEvent(event);
        }
        bh.consume(agg);
    }

    @Benchmark
    public void replayPath(Blackhole bh) {
        BenchmarkAggregate agg = new BenchmarkAggregate();
        for (DomainEvent event : preBuiltEvents) {
            agg.replay(event);
        }
        bh.consume(agg);
    }

    @Benchmark
    public void recordThenDrainCommitted(Blackhole bh) {
        BenchmarkAggregate agg = new BenchmarkAggregate();
        for (DomainEvent event : preBuiltEvents) {
            agg.recordEvent(event);
        }
        bh.consume(agg.getUncommittedEvents());
        agg.markCommitted(eventsPerAggregate);
        bh.consume(agg);
    }
}

package net.nexus_flow.benchmarks;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.eventsourcing.AppendResult;
import net.nexus_flow.core.eventsourcing.InMemoryEventStore;
import net.nexus_flow.core.eventsourcing.StreamId;
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
 * Event-store append + load round-trip. Pins:
 *
 * <ul>
 * <li>{@code append(stream, expectedVersion, events)} — optimistic-concurrency check + slot
 * assignment + replay-index update.
 * <li>{@code readSlice} — head-walk a skiplist starting at the cursor (no full scan).
 * </ul>
 *
 * <p>{@link #eventsPerAppend} sweeps 1 (every command produces one event — common) up to 50
 * (rare large-aggregate commits) so the per-event marginal cost is derivable from the curve.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EventStoreAppendLoadBenchmark {

    public static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        public Bumped(String aggId) {
            super(aggId);
        }
    }

    @Param({"1", "10", "50"})
    public int eventsPerAppend;

    private InMemoryEventStore eventStore;
    private List<DomainEvent>  preBuilt;
    private AtomicLong         streamCounter;

    @Setup
    public void setup() {
        eventStore    = new InMemoryEventStore();
        streamCounter = new AtomicLong();
        preBuilt      = new ArrayList<>(eventsPerAppend);
        for (int i = 0; i < eventsPerAppend; i++) {
            preBuilt.add(new Bumped("agg-bench"));
        }
    }

    @Benchmark
    public void appendOnly(Blackhole bh) {
        StreamId     stream = StreamId.of(Bumped.class, java.util.UUID.fromString(
                                                                                  "00000000-0000-0000-0000-" + String.format("%012d",
                                                                                                                             streamCounter.incrementAndGet())));
        AppendResult result = eventStore.append(stream, 0L, preBuilt);
        bh.consume(result);
    }

    @Benchmark
    public void appendThenLoadAll(Blackhole bh) {
        StreamId stream = StreamId.of(Bumped.class, java.util.UUID.fromString(
                                                                              "00000000-0000-0000-0000-" + String.format("%012d",
                                                                                                                         streamCounter.incrementAndGet())));
        eventStore.append(stream, 0L, preBuilt);
        bh.consume(eventStore.read(stream, 0L, Long.MAX_VALUE));
    }
}

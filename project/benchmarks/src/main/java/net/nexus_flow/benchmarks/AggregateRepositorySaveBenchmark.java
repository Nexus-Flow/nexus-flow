package net.nexus_flow.benchmarks;

import java.io.Serial;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.eventsourcing.AggregateRepository;
import net.nexus_flow.core.eventsourcing.InMemoryEventStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * End-to-end {@link AggregateRepository} save path: aggregate.recordEvent → repo.save →
 * eventStore.append → markCommitted (with the surgical-removal ambient-sink fix) →
 * snapshot-policy check.
 *
 * <p>Measures the per-save cost an application pays in the canonical event-sourcing pattern.
 * No outbox is wired here; outbox-bound save cost is covered by
 * {@link OutboxFullDurableDispatchBenchmark}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class AggregateRepositorySaveBenchmark {

    public static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        public Bumped(String aggId) {
            super(aggId);
        }
    }

    public static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String aggId;

        public Counter(String aggId) {
            this.aggId = aggId;
        }

        public void bump() {
            recordEvent(new Bumped(aggId));
        }

        public String aggId() {
            return aggId;
        }
    }

    private InMemoryEventStore                              eventStore;
    private AggregateRepository<Counter>                    repository;
    private AtomicLong                                      idCounter;

    @Setup
    public void setup() {
        eventStore = new InMemoryEventStore();
        repository = AggregateRepository.builder(
                                                 eventStore, Counter.class, () -> new Counter("placeholder"))
                .build();
        idCounter = new AtomicLong();
    }

    @Benchmark
    public void saveOneEventAggregate(Blackhole bh) {
        String  aggId = "agg-" + idCounter.incrementAndGet();
        Counter c     = new Counter(aggId);
        c.bump();
        repository.save(c);
        bh.consume(c);
    }
}

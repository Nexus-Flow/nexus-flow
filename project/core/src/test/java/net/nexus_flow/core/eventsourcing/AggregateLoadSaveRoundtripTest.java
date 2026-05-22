package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AggregateRepository} save-then-load round-trip reconstitutes identical aggregate
 * state.
 */
class AggregateLoadSaveRoundtripTest {

    static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private UUID              id;
        private int               value;

        Counter() {
            /* hydration ctor */
        }

        Counter(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void increment(int by) {
            recordEvent(new Incremented(id.toString(), by));
        }

        @Override
        protected void apply(DomainEvent event) {
            if (event instanceof Incremented inc) {
                if (id == null) {
                    // Reconstructed via replay before getAggregateId() is set —
                    // fish the id back out of the event payload.
                    id = UUID.fromString(inc.getAggregateId());
                }
                value += inc.by();
            }
        }

        int value() {
            return value;
        }
    }

    static final class Incremented extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        private final int         by;

        Incremented(String aggId, int by) {
            super(aggId);
            this.by = by;
        }

        int by() {
            return by;
        }
    }

    @Test
    void save_then_load_reconstitutes() {
        EventStore store = new InMemoryEventStore();
        UUID       id    = UUID.randomUUID();

        AggregateRepository<Counter> repo =
                new AggregateRepository<>(store, Counter.class, Counter::new);

        Counter c = new Counter(id);
        c.increment(3);
        c.increment(5);
        c.increment(2);
        assertEquals(3, c.getUncommittedEvents().size());
        assertEquals(0L, c.version(), "version is 0 before save");

        repo.save(c);
        assertEquals(3L, c.version(), "version is 3 after save");
        assertTrue(c.getUncommittedEvents().isEmpty());

        // Reload
        Counter reloaded = repo.load(id);
        assertEquals(3L, reloaded.version());
        assertEquals(10, reloaded.value());
        assertEquals(id, reloaded.getAggregateId());
        assertTrue(reloaded.getUncommittedEvents().isEmpty());
    }

    @Test
    void multipleSaves_versionAdvancesMonotonically() {
        EventStore                   store = new InMemoryEventStore();
        UUID                         id    = UUID.randomUUID();
        AggregateRepository<Counter> repo  =
                new AggregateRepository<>(store, Counter.class, Counter::new);

        Counter c = new Counter(id);
        c.increment(1);
        repo.save(c);
        assertEquals(1L, c.version());

        c.increment(2);
        repo.save(c);
        assertEquals(2L, c.version());

        Counter reloaded = repo.load(id);
        assertEquals(2L, reloaded.version());
        assertEquals(3, reloaded.value());
    }
}

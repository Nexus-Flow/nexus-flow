package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Serial;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import org.junit.jupiter.api.Test;

/**
 * Verifies two repositories load the same aggregate, both mutate, both save. First save wins;
 * second raises {@link OptimisticConcurrencyException}.
 */
class OptimisticConcurrencyDetectedOnSaveTest {

    static final class Bag extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private UUID              id;

        Bag() {
        }

        Bag(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void add() {
            recordEvent(new Added(id.toString()));
        }

        @Override
        protected void apply(net.nexus_flow.core.ddd.DomainEvent event) {
            if (event instanceof Added a && id == null) {
                id = UUID.fromString(a.getAggregateId());
            }
        }
    }

    static final class Added extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Added(String aggId) {
            super(aggId);
        }
    }

    @Test
    void secondSave_atSameVersion_raises() {
        EventStore               store = new InMemoryEventStore();
        UUID                     id    = UUID.randomUUID();
        AggregateRepository<Bag> repo  = new AggregateRepository<>(store, Bag.class, Bag::new);

        // Seed the stream with 3 events so both repos load at version=3.
        Bag seed = new Bag(id);
        seed.add();
        seed.add();
        seed.add();
        repo.save(seed);
        assertEquals(3L, seed.version());

        Bag repo1 = repo.load(id);
        Bag repo2 = repo.load(id);
        assertEquals(3L, repo1.version());
        assertEquals(3L, repo2.version());

        repo1.add();
        repo2.add();

        // First save wins.
        repo.save(repo1);
        assertEquals(4L, repo1.version());

        // Second save fails with expected=3, actual=4.
        OptimisticConcurrencyException ex =
                assertThrows(OptimisticConcurrencyException.class, () -> repo.save(repo2));
        assertEquals(3L, ex.expectedVersion());
        assertEquals(4L, ex.actualVersion());

        // The second aggregate's uncommitted buffer is untouched —
        // the caller can rebase and retry.
        assertEquals(1, repo2.getUncommittedEvents().size());
        assertEquals(3L, repo2.version());
    }
}

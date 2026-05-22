package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link AggregateRepository.Builder#readBatchSize(long)} as the configurable knob that
 * previously lived as the hardcoded {@link AggregateRepository#MAX_READ_BATCH} constant.
 *
 * <p>The default remains {@link AggregateRepository#MAX_READ_BATCH} (10 000); operators with very
 * long-lived aggregates can raise it, and tests / memory-constrained deployments can lower it.
 * Validation rejects sizes below 1.
 */
class AggregateRepositoryReadBatchSizeTest {

    static final class Beat extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Beat(String id) {
            super(id);
        }
    }

    static final class Heart extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        private final UUID      id;
        final List<DomainEvent> seen = new ArrayList<>();

        Heart(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void beat() {
            recordEvent(new Beat(id.toString()));
        }

        @Override
        protected void apply(DomainEvent event) {
            seen.add(event);
        }
    }

    @Test
    void smallBatch_replaysCorrectly_acrossMultipleRoundTrips() {
        InMemoryEventStore store = new InMemoryEventStore();
        UUID               id    = UUID.randomUUID();

        // Append 25 events so the batch loop must iterate (with batchSize=3 → ~9 round-trips).
        Heart writer = new Heart(id);
        for (int i = 0; i < 25; i++)
            writer.beat();
        StreamId stream = new StreamId(Heart.class.getName(), id);
        store.append(stream, 0L, writer.drainEvents());

        AggregateRepository<Heart> repo =
                AggregateRepository.builder(store, Heart.class, () -> new Heart(id))
                        .readBatchSize(3L)
                        .build();

        Heart loaded = repo.load(id);
        assertEquals(25, loaded.seen.size(), "all 25 events must be replayed");
        assertEquals(25L, loaded.version(), "version must equal envelope count");
    }

    @Test
    void defaultBatch_isMaxReadBatch_constant() {
        InMemoryEventStore store = new InMemoryEventStore();
        UUID               id    = UUID.randomUUID();

        // Build via the default ctor — must use MAX_READ_BATCH; we cannot directly read it but we
        // can verify the load works with a tiny dataset (regression: the default constructor must
        // wire SOMETHING reasonable into the new readBatchSize field).
        AggregateRepository<Heart> repo   =
                new AggregateRepository<>(store, Heart.class, () -> new Heart(id));
        Heart                      loaded = repo.load(id);
        assertEquals(0, loaded.seen.size(), "fresh stream loads empty aggregate");
        // Constant is still publicly visible for backwards-compat reference.
        assertEquals(10_000L, AggregateRepository.MAX_READ_BATCH);
    }

    @Test
    void builder_rejectsBatchSizeBelow1() {
        InMemoryEventStore store = new InMemoryEventStore();
        UUID               id    = UUID.randomUUID();
        assertThrows(
                     IllegalArgumentException.class,
                     () -> AggregateRepository.builder(store, Heart.class, () -> new Heart(id))
                             .readBatchSize(0L)
                             .build());
        assertThrows(
                     IllegalArgumentException.class,
                     () -> AggregateRepository.builder(store, Heart.class, () -> new Heart(id))
                             .readBatchSize(-1L)
                             .build());
    }
}

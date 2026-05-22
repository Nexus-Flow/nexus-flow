package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Verifies that when a snapshot exists at version=50 over a 100-event stream, the repository reads
 * exactly 50 envelopes (51..100), not 100.
 */
class SnapshotShortCircuitsReplayTest {

    static final class Acc extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private UUID              id;
        private int               sum;

        Acc() {
        }

        Acc(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void bump() {
            recordEvent(new Bumped(id.toString()));
        }

        @Override
        protected void apply(DomainEvent event) {
            if (event instanceof Bumped b) {
                if (id == null)
                    id = UUID.fromString(b.getAggregateId());
                sum++;
            }
        }

        @Override
        public void applySnapshotState(byte[] state, String stateType) {
            // The snapshot stores sum as 4 bytes big-endian.
            this.sum =
                    ((state[0] & 0xFF) << 24) | ((state[1] & 0xFF) << 16) | ((state[2] & 0xFF) << 8) | (state[3] & 0xFF);
        }

        int sum() {
            return sum;
        }
    }

    static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Bumped(String aggId) {
            super(aggId);
        }
    }

    /** Decorator that counts {@code read} calls and how many envelopes the store delivered. */
    static final class CountingEventStore implements EventStore {
        final EventStore    delegate;
        final AtomicInteger envelopesRead = new AtomicInteger();

        CountingEventStore(EventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AppendResult append(StreamId stream, long expectedVersion, List<DomainEvent> events) {
            return delegate.append(stream, expectedVersion, events);
        }

        @Override
        public EventStream read(StreamId stream, long fromVersion, long maxCount) {
            EventStream slice = delegate.read(stream, fromVersion, maxCount);
            envelopesRead.addAndGet(slice.size());
            return slice;
        }

        @Override
        public EventStream readAll(long fromGlobalPosition, long maxCount) {
            return delegate.readAll(fromGlobalPosition, maxCount);
        }
    }

    @Test
    void snapshot_atVersion50_readsOnly50_not100() {
        InMemoryEventStore raw = new InMemoryEventStore();
        UUID               id  = UUID.randomUUID();

        // Seed 100 events.
        AggregateRepository<Acc> seed = new AggregateRepository<>(raw, Acc.class, Acc::new);
        Acc                      a    = new Acc(id);
        for (int i = 0; i < 100; i++)
            a.bump();
        seed.save(a);
        assertEquals(100L, a.version());

        // Persist a snapshot at version=50 with state=50.
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        byte[]                state     = new byte[]{0, 0, 0, 50};
        snapshots.save(new Snapshot(new StreamId(Acc.class.getName(), id), 50L, state, "test/int4be"));

        // Load through a counting store + repo wired with the snapshot store.
        CountingEventStore       counting = new CountingEventStore(raw);
        AggregateRepository<Acc> repo     =
                new AggregateRepository<>(counting, Acc.class, Acc::new, snapshots);

        Acc loaded = repo.load(id);

        // 50 envelopes replayed (51..100), not 100.
        assertEquals(
                     50,
                     counting.envelopesRead.get(),
                     "AggregateRepository must short-circuit past the snapshot");
        // Final state: snapshot(=50) + 50 bumps = 100.
        assertEquals(100, loaded.sum());
        assertEquals(100L, loaded.version());
        assertTrue(loaded.getUncommittedEvents().isEmpty());
    }
}

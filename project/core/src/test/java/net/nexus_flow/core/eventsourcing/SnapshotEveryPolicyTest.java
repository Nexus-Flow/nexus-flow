package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.Optional;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code snapshotEvery(N)} auto-snapshot policy for {@link AggregateRepository}:
 * disabled by default, captures state at multiples of N when enabled, respects opt-out.
 */
class SnapshotEveryPolicyTest {

    static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Bumped(String aggId) {
            super(aggId);
        }
    }

    /** Aggregate that captures its sum as 4 big-endian bytes. */
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
            this.sum =
                    ((state[0] & 0xFF) << 24) | ((state[1] & 0xFF) << 16) | ((state[2] & 0xFF) << 8) | (state[3] & 0xFF);
        }

        @Override
        public Optional<SnapshotState> captureSnapshotState() {
            byte[] s =
                    new byte[]{(byte) (sum >>> 24), (byte) (sum >>> 16), (byte) (sum >>> 8), (byte) sum
                    };
            return Optional.of(new SnapshotState(s, "test/int4be"));
        }

        int sum() {
            return sum;
        }
    }

    /** Aggregate that does NOT override captureSnapshotState — opts out. */
    static final class OptOutAcc extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private UUID              id;

        OptOutAcc() {
        }

        OptOutAcc(UUID id) {
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
            if (event instanceof Bumped b && id == null) {
                id = UUID.fromString(b.getAggregateId());
            }
        }
    }

    @Test
    void policy_disabled_neverWrites() {
        InMemoryEventStore    store     = new InMemoryEventStore();
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        UUID                  id        = UUID.randomUUID();

        // Legacy ctor: snapshotStore is set but snapshotEvery == 0.
        AggregateRepository<Acc> repo =
                new AggregateRepository<>(store, Acc.class, Acc::new, snapshots);
        assertEquals(0, repo.snapshotEvery());

        Acc a = new Acc(id);
        for (int i = 0; i < 25; i++)
            a.bump();
        repo.save(a);

        assertTrue(
                   snapshots.load(new StreamId(Acc.class.getName(), id)).isEmpty(),
                   "snapshotEvery == 0 must never write a snapshot");
    }

    @Test
    void policy_enabled_writesAtMultiples() {
        InMemoryEventStore    store     = new InMemoryEventStore();
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        UUID                  id        = UUID.randomUUID();
        StreamId              stream    = new StreamId(Acc.class.getName(), id);

        AggregateRepository<Acc> repo =
                AggregateRepository.builder(store, Acc.class, Acc::new)
                        .snapshotStore(snapshots)
                        .snapshotEvery(10)
                        .build();
        assertEquals(10, repo.snapshotEvery());

        // Round 1 — save at version=7, NOT a multiple of 10 → no snapshot.
        Acc a = new Acc(id);
        for (int i = 0; i < 7; i++)
            a.bump();
        repo.save(a);
        assertTrue(snapshots.load(stream).isEmpty(), "no snapshot expected at version=7");

        // Round 2 — three more bumps land us at version=10 → snapshot fires.
        for (int i = 0; i < 3; i++)
            a.bump();
        repo.save(a);
        var snap = snapshots.load(stream);
        assertTrue(snap.isPresent(), "snapshot expected at version=10");
        assertEquals(10L, snap.get().version());

        // Round 3 — bump to version=20 → snapshot updated.
        for (int i = 0; i < 10; i++)
            a.bump();
        repo.save(a);
        snap = snapshots.load(stream);
        assertTrue(snap.isPresent());
        assertEquals(20L, snap.get().version());

        // Final replay through the snapshot path — sum is preserved.
        Acc reloaded = repo.load(id);
        assertEquals(20, reloaded.sum());
        assertEquals(20L, reloaded.version());
    }

    @Test
    void aggregate_optsOut_neverSnapshots() {
        InMemoryEventStore    store     = new InMemoryEventStore();
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        UUID                  id        = UUID.randomUUID();

        AggregateRepository<OptOutAcc> repo =
                AggregateRepository.builder(store, OptOutAcc.class, OptOutAcc::new)
                        .snapshotStore(snapshots)
                        .snapshotEvery(5)
                        .build();

        OptOutAcc a = new OptOutAcc(id);
        for (int i = 0; i < 5; i++)
            a.bump();
        repo.save(a);

        assertFalse(
                    snapshots.load(new StreamId(OptOutAcc.class.getName(), id)).isPresent(),
                    "aggregate that returns empty captureSnapshotState() must not be snapshotted");
    }

    @Test
    void builder_rejects_missingStore() {
        InMemoryEventStore       store = new InMemoryEventStore();
        IllegalArgumentException ex    =
                assertThrows(
                             IllegalArgumentException.class,
                             () -> AggregateRepository.builder(store, Acc.class, Acc::new).snapshotEvery(10).build());
        assertTrue(ex.getMessage().contains("SnapshotStore"));
    }

    @Test
    void builder_rejects_negative() {
        InMemoryEventStore    store     = new InMemoryEventStore();
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        assertThrows(
                     IllegalArgumentException.class,
                     () -> AggregateRepository.builder(store, Acc.class, Acc::new)
                             .snapshotStore(snapshots)
                             .snapshotEvery(-1)
                             .build());
    }
}

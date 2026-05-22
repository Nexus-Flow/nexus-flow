package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link ProjectionRunner}'s snapshot-on-resume contract:
 *
 * <ol>
 * <li>A runner with a snapshot store + non-zero {@code snapshotEvery} that finds a saved
 * snapshot calls {@link Projection#applySnapshotState(byte[], String)} and resumes
 * envelope replay from {@code snapshot.globalPosition() + 1}.
 * <li>A runner whose {@code snapshotEvery} threshold is crossed calls {@link
 * Projection#captureSnapshotState()} and persists the result.
 * <li>Projections that opt out of snapshots (default {@link Optional#empty()}) silently
 * skip the write; the runner remains correct but never persists a snapshot.
 * <li>Constructor rejects {@code snapshotEvery > 0} without a snapshot store.
 * </ol>
 */
class ProjectionSnapshotResumptionTest {

    static final class Marker extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Marker(String aggId) {
            super(aggId);
        }
    }

    /**
     * Test projection that stores a running counter in its snapshot blob. Production
     * projections would persist a richer read model — here a single long is enough to
     * verify the snapshot round-trip.
     */
    static final class CountingProjection implements Projection {
        long count;
        long checkpoint;

        @Override
        public String name() {
            return "counter";
        }

        @Override
        public void apply(EventEnvelope envelope) {
            count++;
            checkpoint = envelope.globalPosition();
        }

        @Override
        public long checkpoint() {
            return checkpoint;
        }

        @Override
        public Optional<CapturedState> captureSnapshotState() {
            return Optional.of(new CapturedState(
                    Long.toString(count).getBytes(StandardCharsets.UTF_8),
                    "counter:v1"));
        }

        @Override
        public void applySnapshotState(byte[] state, String stateType) {
            assertEquals("counter:v1", stateType);
            count = Long.parseLong(new String(state, StandardCharsets.UTF_8));
        }
    }

    @Test
    void newRunner_loadsExistingSnapshot_andResumesAfterIt() {
        EventStore                      store     = new InMemoryEventStore();
        ProjectionCheckpointStore       cps       = new InMemoryProjectionCheckpointStore();
        InMemoryProjectionSnapshotStore snapshots = new InMemoryProjectionSnapshotStore();
        UUID                            agg       = UUID.randomUUID();
        StreamId                        s         = new StreamId("test.X", agg);
        for (int i = 0; i < 10; i++) {
            store.append(s, i, List.of(new Marker(agg.toString())));
        }
        // Pre-seed a snapshot at globalPosition=6 carrying count=6.
        snapshots.save(new ProjectionSnapshot(
                "counter", 6L,
                "6".getBytes(StandardCharsets.UTF_8),
                "counter:v1"));

        CountingProjection proj = new CountingProjection();
        try (ProjectionRunner runner = new ProjectionRunner(
                store, cps, proj, ProjectionRunnerConfig.DEFAULTS, snapshots, 100)) {
            long applied = runner.catchUp();
            // Snapshot loaded → count == 6 already. Only envelopes at positions 7..10 applied
            // (4 envelopes). Final count == 10.
            assertEquals(4L, applied);
            assertEquals(10L, proj.count,
                         "snapshot pre-seed + replay of tail MUST land at total = 10");
        }
    }

    @Test
    void runner_persistsSnapshot_afterEverySnapshotEveryEnvelopes() {
        EventStore                      store     = new InMemoryEventStore();
        ProjectionCheckpointStore       cps       = new InMemoryProjectionCheckpointStore();
        InMemoryProjectionSnapshotStore snapshots = new InMemoryProjectionSnapshotStore();
        UUID                            agg       = UUID.randomUUID();
        StreamId                        s         = new StreamId("test.X", agg);
        for (int i = 0; i < 5; i++) {
            store.append(s, i, List.of(new Marker(agg.toString())));
        }

        CountingProjection proj = new CountingProjection();
        try (ProjectionRunner runner = new ProjectionRunner(
                store, cps, proj, ProjectionRunnerConfig.DEFAULTS, snapshots, 3)) {
            runner.catchUp();
            // 5 envelopes applied; snapshot threshold of 3 means a snapshot was written at
            // envelope 3 (counter reset → 5 - 3 = 2 outstanding). Latest snapshot persisted.
            Optional<ProjectionSnapshot> snap = snapshots.load("counter");
            assertTrue(snap.isPresent(),
                       "after crossing snapshotEvery=3 the runner MUST persist a snapshot");
            // The snapshot was taken at the 3rd envelope's globalPosition.
            assertEquals(3L, snap.get().globalPosition());
        }
    }

    @Test
    void projectionWithoutSnapshotSupport_runnerWorks_butNeverPersists() {
        // A projection that does NOT override captureSnapshotState — same shape as the
        // pre-snapshot Projection contract — must still work end-to-end with a snapshot
        // store wired; the store stays empty.
        Projection plain = new Projection() {
            long position;

            @Override
            public String name() {
                return "plain";
            }

            @Override
            public void apply(EventEnvelope e) {
                position = e.globalPosition();
            }

            @Override
            public long checkpoint() {
                return position;
            }
        };

        EventStore                      store     = new InMemoryEventStore();
        ProjectionCheckpointStore       cps       = new InMemoryProjectionCheckpointStore();
        InMemoryProjectionSnapshotStore snapshots = new InMemoryProjectionSnapshotStore();
        UUID                            agg       = UUID.randomUUID();
        StreamId                        s         = new StreamId("test.X", agg);
        for (int i = 0; i < 5; i++) {
            store.append(s, i, List.of(new Marker(agg.toString())));
        }
        try (ProjectionRunner runner = new ProjectionRunner(
                store, cps, plain, ProjectionRunnerConfig.DEFAULTS, snapshots, 2)) {
            runner.catchUp();
            assertEquals(0, snapshots.size(),
                         "opt-out projection MUST NOT cause snapshot writes");
        }
        assertNotNull(plain.toString()); // sanity touch — the projection ran without crashing
    }

    @Test
    void snapshotEveryGreaterThanZero_withoutStore_rejectedAtConstruction() {
        EventStore                store = new InMemoryEventStore();
        ProjectionCheckpointStore cps   = new InMemoryProjectionCheckpointStore();
        CountingProjection        proj  = new CountingProjection();
        assertThrows(IllegalArgumentException.class,
                     () -> new ProjectionRunner(
                             store, cps, proj, ProjectionRunnerConfig.DEFAULTS, null, 10));
    }

    @Test
    void snapshotEveryNegative_rejectedAtConstruction() {
        EventStore                      store     = new InMemoryEventStore();
        ProjectionCheckpointStore       cps       = new InMemoryProjectionCheckpointStore();
        InMemoryProjectionSnapshotStore snapshots = new InMemoryProjectionSnapshotStore();
        CountingProjection              proj      = new CountingProjection();
        assertThrows(IllegalArgumentException.class,
                     () -> new ProjectionRunner(
                             store, cps, proj, ProjectionRunnerConfig.DEFAULTS, snapshots, -1));
    }
}

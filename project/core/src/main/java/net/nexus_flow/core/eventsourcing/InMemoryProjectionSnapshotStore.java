package net.nexus_flow.core.eventsourcing;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ProjectionSnapshotStore} for tests and single-node demos.
 *
 * <p>Keeps one entry per projection name in a {@link ConcurrentHashMap}. Lock-free reads and
 * writes; saves are atomic per name via the map's {@code put} primitive. Not durable across
 * restarts — production deployments wire a JDBC / S3 / object-store-backed adapter.
 */
public final class InMemoryProjectionSnapshotStore implements ProjectionSnapshotStore {

    private final ConcurrentHashMap<String, ProjectionSnapshot> projectionSnapshotIndex = new ConcurrentHashMap<>();

    @Override
    public void save(ProjectionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        projectionSnapshotIndex.put(snapshot.projectionName(), snapshot);
    }

    @Override
    public Optional<ProjectionSnapshot> load(String projectionName) {
        Objects.requireNonNull(projectionName, "projectionName");
        return Optional.ofNullable(projectionSnapshotIndex.get(projectionName));
    }

    /** Diagnostic — number of distinct projections that have a persisted snapshot. */
    public int size() {
        return projectionSnapshotIndex.size();
    }

    /** Wipe every snapshot — convenience for test teardown. */
    public void clear() {
        projectionSnapshotIndex.clear();
    }
}

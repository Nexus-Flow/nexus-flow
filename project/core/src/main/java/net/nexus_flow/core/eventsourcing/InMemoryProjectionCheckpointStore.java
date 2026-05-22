package net.nexus_flow.core.eventsourcing;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ProjectionCheckpointStore}.
 *
 * <p>Backed by a {@link ConcurrentHashMap}; {@link ConcurrentHashMap#merge} guarantees atomic
 * compare-and-swap semantics — concurrent saves for the same projection name are safe without
 * external locking.
 */
public final class InMemoryProjectionCheckpointStore implements ProjectionCheckpointStore {

    private final ConcurrentHashMap<String, Long> projectionCheckpointIndex = new ConcurrentHashMap<>();

    /**
     * Load the checkpoint recorded for {@code projectionName}.
     *
     * @param projectionName projection identity
     * @return persisted checkpoint, or {@code 0} when the projection has never run
     * @throws NullPointerException if {@code projectionName} is {@code null}
     */
    @Override
    public long load(String projectionName) {
        Objects.requireNonNull(projectionName, "projectionName");
        Long v = projectionCheckpointIndex.get(projectionName);
        return v == null ? 0L : v;
    }

    /**
     * Persist the maximum checkpoint seen for {@code projectionName}.
     *
     * @param projectionName projection identity
     * @param globalPosition checkpoint to persist
     * @throws NullPointerException     if {@code projectionName} is {@code null}
     * @throws IllegalArgumentException if {@code globalPosition < 0}
     */
    @Override
    public void save(String projectionName, long globalPosition) {
        Objects.requireNonNull(projectionName, "projectionName");
        if (globalPosition < 0) {
            throw new IllegalArgumentException("globalPosition must be >= 0: " + globalPosition);
        }
        projectionCheckpointIndex.merge(projectionName, globalPosition, Math::max);
    }
}

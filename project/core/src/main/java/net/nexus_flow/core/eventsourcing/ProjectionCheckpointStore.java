package net.nexus_flow.core.eventsourcing;

/**
 * Persistent storage for projection checkpoints (one global position per projection name).
 *
 * <p>Implementations must be thread-safe: concurrent reads and writes from different threads with
 * different projection names are allowed.
 */
public interface ProjectionCheckpointStore {

    /**
     * Return the persisted checkpoint, or {@code 0} if unknown.
     *
     * @param projectionName the projection identity; non-null
     * @return last persisted global position, or {@code 0}
     */
    long load(String projectionName);

    /**
     * Persist the new checkpoint.
     *
     * @param projectionName the projection identity; non-null
     * @param globalPosition the global position to record; must be &gt;= 0
     */
    void save(String projectionName, long globalPosition);
}

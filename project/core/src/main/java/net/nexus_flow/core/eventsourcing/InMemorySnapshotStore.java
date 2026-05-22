package net.nexus_flow.core.eventsourcing;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SnapshotStore}. Out-of-order writes (lower version arriving after a higher one)
 * are silently dropped via {@link ConcurrentHashMap#merge} — no external locking required.
 */
public final class InMemorySnapshotStore implements SnapshotStore {

    private final ConcurrentHashMap<StreamId, Snapshot> byStream = new ConcurrentHashMap<>();

    /**
     * Return the highest-version snapshot stored for {@code stream}.
     *
     * @param stream stream whose snapshot should be loaded
     * @return the latest snapshot, or empty when none has been persisted
     * @throws NullPointerException if {@code stream} is {@code null}
     */
    @Override
    public Optional<Snapshot> load(StreamId stream) {
        Objects.requireNonNull(stream, "stream");
        return Optional.ofNullable(byStream.get(stream));
    }

    /**
     * Save {@code snapshot} if it is newer than, or equal in version to, the currently stored one.
     *
     * @param snapshot snapshot to persist
     * @throws NullPointerException if {@code snapshot} is {@code null}
     */
    @Override
    public void save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        byStream.merge(
                       snapshot.stream(),
                       snapshot,
                       (prev, next) -> next.version() >= prev.version() ? next : prev);
    }
}

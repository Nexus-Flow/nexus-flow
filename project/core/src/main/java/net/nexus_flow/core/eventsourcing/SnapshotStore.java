package net.nexus_flow.core.eventsourcing;

import java.util.Optional;

/**
 * Pluggable snapshot persistence.
 *
 * <p>This SPI remains intentionally open for future JDBC, JPA, or log-compacted implementations.
 * Snapshots are an optimization only: they capture aggregate state at a committed stream version so
 * {@link AggregateRepository#load(java.util.UUID)} can restore that state first and replay only the
 * tail of the stream.
 *
 * <p>Implementations:
 *
 * <ul>
 * <li>{@link InMemorySnapshotStore} — tests, demos.
 * </ul>
 */
public interface SnapshotStore {

    /**
     * Return the most recent snapshot for {@code stream}, if any.
     *
     * <p>Repositories call this before replaying events. If a snapshot is returned, {@link
     * AggregateRepository#load(java.util.UUID)} hydrates aggregate state from the snapshot payload
     * and then resumes event replay at {@code snapshot.version() + 1}. Implementations may keep
     * multiple historical snapshots internally, but the contract exposed here is "return the
     * highest-version snapshot stored so far".
     *
     * @param stream the stream to look up; non-null
     * @return the highest-version snapshot, or empty
     * @throws NullPointerException if {@code stream} is {@code null}
     */
    Optional<Snapshot> load(StreamId stream);

    /**
     * Persist {@code snapshot}.
     *
     * <p>{@link AggregateRepository#save(net.nexus_flow.core.ddd.Aggregate)} typically calls this
     * after a successful append when its {@code snapshotEvery(N)} policy fires. The snapshot payload
     * is opaque to the store: {@link Snapshot#state()} holds the serialized state bytes and {@link
     * Snapshot#stateType()} identifies the encoding format so future migrations can branch on it.
     *
     * <p>Implementations should treat lower-version snapshots arriving after a higher-version one as
     * a no-op, or otherwise preserve the highest committed snapshot for the stream.
     *
     * @param snapshot the snapshot to persist; non-null
     * @throws NullPointerException if {@code snapshot} is {@code null}
     */
    void save(Snapshot snapshot);
}

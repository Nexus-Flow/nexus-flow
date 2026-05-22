package net.nexus_flow.core.eventsourcing;

import java.util.Optional;

/**
 * Persistent storage for projection snapshots. Optional collaborator of {@link
 * ProjectionRunner}: without it the runner replays envelopes from global-position 0 every time
 * the projection starts cold; with it the runner loads the latest snapshot, hands it to the
 * projection's {@link Projection#applySnapshotState(byte[], String)} hook, and resumes from
 * {@code snapshot.globalPosition() + 1}.
 *
 * <h2>Concurrency model</h2>
 *
 * Implementations MUST be thread-safe. A single runner per projection name is the standard
 * deployment shape (the snapshot column is a single row keyed by name), but adapter modules
 * may parallelise saves across different projection names freely. The store does NOT enforce
 * "one runner per name" — that contract is the runner's responsibility via lease coordination
 * or other external means.
 *
 * <h2>Retention semantics</h2>
 *
 * The store keeps the LATEST snapshot per projection name. {@link #save(ProjectionSnapshot)}
 * overwrites the previous snapshot for the same name; {@link #load(String)} returns the most
 * recent. JDBC adapters back this with {@code INSERT ... ON CONFLICT (projection_name) DO
 * UPDATE} (Postgres) or the equivalent upsert primitive on other dialects. Older snapshots
 * are not retained because the projection's state at {@code globalPosition = N} is fully
 * recoverable from any snapshot at {@code M <= N} + replay of envelopes {@code M+1..N}.
 */
public interface ProjectionSnapshotStore {

    /**
     * Persist a snapshot. Overwrites any previous snapshot for the same {@link
     * ProjectionSnapshot#projectionName()}.
     *
     * @param snapshot the snapshot to persist; never {@code null}
     * @throws NullPointerException if {@code snapshot} is {@code null}
     */
    void save(ProjectionSnapshot snapshot);

    /**
     * Return the latest persisted snapshot for {@code projectionName}, or {@link
     * Optional#empty()} when no snapshot exists yet.
     *
     * @param projectionName the projection identity; never {@code null}
     * @return the latest snapshot, or empty
     * @throws NullPointerException if {@code projectionName} is {@code null}
     */
    Optional<ProjectionSnapshot> load(String projectionName);
}

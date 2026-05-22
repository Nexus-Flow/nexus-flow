package net.nexus_flow.core.eventsourcing;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Read-model builder fed by an {@link EventStore}.
 *
 * <p>A projection consumes envelopes in global-position order via a {@link ProjectionRunner}. Its
 * {@link #name()} identifies the persisted checkpoint (column PK in {@link
 * ProjectionCheckpointStore}); two projections with the same name share the same checkpoint and
 * therefore <strong>must not</strong> run concurrently against the same store.
 *
 * <p>{@link #apply(EventEnvelope)} is called sequentially by the runner with envelopes in strictly
 * increasing {@code globalPosition} order. Implementations need not be thread-safe — the runner
 * serializes calls — but they should be idempotent on duplicate applies in case of a crash between
 * {@link #apply(EventEnvelope)} and the checkpoint write.
 *
 * <h2>Snapshot support (optional)</h2>
 *
 * Long-lived projections with bounded read-model size opt in to snapshots: the runner periodically
 * calls {@link #captureSnapshotState()} to serialise the current state and persists the result
 * through {@link ProjectionSnapshotStore}. On the next cold start the runner loads the latest
 * snapshot, calls {@link #applySnapshotState(byte[], String)} to rehydrate the in-memory state,
 * and resumes from {@code snapshot.globalPosition() + 1} — saving the cost of replaying every
 * envelope from global-position 0.
 *
 * <p>Projections that do not implement the hooks (the default returns {@link Optional#empty()})
 * always replay from 0; this matches the pre-snapshot behaviour and is fine for read models that
 * are cheap to rebuild from the full stream.
 */
public interface Projection {

    /**
     * Identity / persisted checkpoint key.
     *
     * @return non-blank stable name used as the checkpoint key across restarts
     */
    String name();

    /**
     * Apply one envelope to the read model.
     *
     * @param envelope the envelope to apply; always in strictly increasing {@code globalPosition}
     *                 order
     */
    void apply(EventEnvelope envelope);

    /**
     * Current checkpoint — the {@code globalPosition} of the most recent {@link
     * #apply(EventEnvelope)} call.
     *
     * @return the last applied global position, or {@code 0} when nothing has been applied yet
     */
    long checkpoint();

    /**
     * Capture the projection's current read-model state for {@link ProjectionSnapshotStore}
     * persistence. The default returns {@link Optional#empty()} — snapshot policy is opt-in
     * per projection. Implementations that override SHOULD return a fresh, immutable snapshot
     * carrying the serialised state + a stable {@code stateType} identifier so the format can
     * evolve.
     *
     * <p>Implementations must NOT mutate the projection's state from inside this method; the
     * runner calls it on the same thread that applies envelopes, but conceptually the snapshot
     * is a read-only observation.
     *
     * @return the captured state, or empty when this projection opts out of snapshots
     */
    default Optional<CapturedState> captureSnapshotState() {
        return Optional.empty();
    }

    /**
     * Optional state-restore hook called by {@link ProjectionRunner} when a snapshot is found
     * for this projection's name. The default is a no-op — projections that override
     * {@link #captureSnapshotState()} must also override this method to consume the bytes.
     *
     * @param state     opaque state bytes from the snapshot; never {@code null}
     * @param stateType identifier of the {@code state} encoding; never {@code null} or blank
     */
    default void applySnapshotState(byte[] state, String stateType) {
        // default: projections without snapshot support take no action
    }

    /**
     * Value carrier for {@link #captureSnapshotState()}. Mirrors {@link
     * net.nexus_flow.core.ddd.Aggregate.SnapshotState} on the aggregate side so projection
     * authors recognise the shape.
     *
     * @param state     opaque state bytes; never {@code null}
     * @param stateType identifier of the {@code state} encoding (e.g.
     *                  {@code "json:OrderReadModel.v1"}); never {@code null} or blank
     */
    record CapturedState(byte[] state, String stateType) {
        public CapturedState {
            java.util.Objects.requireNonNull(state, "state");
            java.util.Objects.requireNonNull(stateType, "stateType");
            if (stateType.isBlank()) {
                throw new IllegalArgumentException("stateType must not be blank");
            }
            state = java.util.Arrays.copyOf(state, state.length);
        }

        @Override
        public byte[] state() {
            return java.util.Arrays.copyOf(state, state.length);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CapturedState(byte[] state1, String type1))) {
                return false;
            }
            return java.util.Arrays.equals(state, state1) && java.util.Objects.equals(stateType, type1);
        }

        @Override
        public int hashCode() {
            int result = java.util.Arrays.hashCode(state);
            return 31 * result + java.util.Objects.hashCode(stateType);
        }
    }
}

package net.nexus_flow.core.saga;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of a saga instance at a point in time, persisted for restart safety.
 *
 * <p><strong>Optimistic concurrency control:</strong> {@link #version()} is the optimistic
 * concurrency token used by {@link SagaStorage#save(SagaState, long)}: a save with {@code
 * expectedVersion != state.version()} is rejected. The first insert uses {@code expectedVersion ==
 * 0}. This prevents lost-update anomalies when multiple processes attempt to write the same saga
 * instance concurrently.
 *
 * <p><strong>Opaque data map:</strong> {@link #data()} is an immutable map — sagas store their
 * domain-specific state here. The storage layer treats it as a blob and never inspects its
 * contents. The correlation key (if any) may be embedded in the data map as {@code
 * "_correlationKey"} for reference.
 *
 * <p><strong>Checkpoint for restart safety:</strong> {@link #lastProcessedGlobalPosition()} records
 * the global event store position of the last envelope the saga successfully processed. On process
 * restart, the runner resumes from this position, ensuring idempotency — already processed
 * envelopes are never replayed.
 *
 * <p><strong>Status lifecycle:</strong> A saga progresses through {@link SagaStatus} states from
 * {@link SagaStatus#RUNNING} to one of three terminal states ({@link SagaStatus#COMPLETED}, {@link
 * SagaStatus#COMPENSATED}, or {@link SagaStatus#FAILED_TERMINAL}). Once terminal, no further state
 * transitions occur.
 */
public record SagaState(
                        SagaId id,
                        String type,
                        SagaStatus status,
                        long version,
                        Map<String, Object> data,
                        Instant createdAt,
                        Instant updatedAt,
                        long lastProcessedGlobalPosition) {

    public SagaState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0: " + version);
        }
        Objects.requireNonNull(data, "data");
        data = Map.copyOf(data);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (lastProcessedGlobalPosition < 0) {
            throw new IllegalArgumentException(
                    "lastProcessedGlobalPosition must be >= 0: " + lastProcessedGlobalPosition);
        }
    }

    /** Create a fresh saga instance in {@link SagaStatus#RUNNING} state at version 0. */
    public static SagaState fresh(SagaId id, String type, Instant now) {
        return new SagaState(id, type, SagaStatus.RUNNING, 0L, Map.of(), now, now, 0L);
    }

    /**
     * Derive a new state by advancing the version, status, checkpoint, and data. The {@code id} and
     * creation timestamp are immutable; the updated timestamp is set to {@code now}. This method
     * enforces the invariant that versions are monotonically increasing across transitions.
     *
     * @param newData       the updated saga data (typically from transition result maps)
     * @param newStatus     the next status ({@link SagaStatus#RUNNING}, {@link SagaStatus#COMPLETED},
     *                      etc.)
     * @param newCheckpoint the global event position of the envelope that triggered this transition
     * @param now           the current time (usually from a {@link java.time.Clock})
     * @return a new {@link SagaState} with version incremented by 1
     */
    public SagaState next(
            Map<String, Object> newData, SagaStatus newStatus, long newCheckpoint, Instant now) {
        return new SagaState(id, type, newStatus, version + 1L, newData, createdAt, now, newCheckpoint);
    }
}

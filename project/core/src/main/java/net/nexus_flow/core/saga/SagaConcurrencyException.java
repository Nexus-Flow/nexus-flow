package net.nexus_flow.core.saga;

import java.io.Serial;

/**
 * Thrown by {@link SagaStorage#save(SagaState, long)} when the optimistic-concurrency check fails.
 *
 * <p><strong>Cause:</strong> This exception indicates that the persisted version of the saga state
 * does not match the expected version passed to {@code save()}. This typically happens when two
 * processes (or threads) attempt to update the same saga instance concurrently, and one succeeds
 * before the other.
 *
 * <p><strong>Recovery:</strong> The caller should reload the saga state (via {@code load()}),
 * inspect the current version, and retry the saga operation from the latest state.
 *
 * <p><strong>Serializability:</strong> {@link SagaId} is a record over a {@link java.util.UUID}
 * (itself {@link java.io.Serializable}), so this field round-trips cleanly. Marked {@code
 * transient} so the exception's wire form is independent of the SagaId class shape; the diagnostic
 * accessors below remain available within the same JVM.
 */
public final class SagaConcurrencyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The saga instance id that experienced the concurrency conflict. Marked {@code transient} for
     * serialization independence.
     */
    private final transient SagaId id;

    private final long expectedVersion;
    private final long actualVersion;

    public SagaConcurrencyException(SagaId id, long expectedVersion, long actualVersion) {
        // Stack-traceless — concurrency conflicts are a normal occurrence under contention
        // (retry-and-rebase is the documented recovery), so each throw is a hot path. The
        // diagnostics live in id + expectedVersion + actualVersion. Saves ~200 ns per
        // conflict throw.
        super(
              "saga concurrency conflict on "
                      + id
                      + ": expected version="
                      + expectedVersion
                      + ", actual="
                      + actualVersion,
              /* cause= */ null,
              // Suppression chain remains active — only the stack trace is skipped.
              /* enableSuppression= */ true,
              /* writableStackTrace= */ false);
        this.id              = id;
        this.expectedVersion = expectedVersion;
        this.actualVersion   = actualVersion;
    }

    /**
     * Return the id of the saga instance that experienced the concurrency conflict.
     *
     * @return the saga instance id; never {@code null}
     */
    public SagaId id() {
        return id;
    }

    /**
     * Return the optimistic-concurrency version that the caller expected to find in storage.
     *
     * @return the expected version
     */
    public long expectedVersion() {
        return expectedVersion;
    }

    /**
     * Return the version that was actually found in storage (or 0 if the slot was empty).
     *
     * @return the actual version
     */
    public long actualVersion() {
        return actualVersion;
    }
}

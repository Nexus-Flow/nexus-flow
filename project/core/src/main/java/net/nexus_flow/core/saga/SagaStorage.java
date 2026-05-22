package net.nexus_flow.core.saga;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistent backing store for saga instances.
 *
 * <p><strong>Addressing:</strong> Sagas are addressed by {@code (type, correlationKey)} — the saga
 * class plus the business id of the instance it tracks. This tuple uniquely identifies one saga
 * instance.
 *
 * <p><strong>Optimistic concurrency control:</strong> The storage implements optimistic concurrency
 * via {@link SagaState#version()}: {@link #save(SagaState, long)} writes only when {@code
 * expectedVersion == currentVersion}. This prevents lost-update anomalies and ensures saga state
 * machine transitions are serialized at the storage layer.
 *
 * <p><strong>Load semantics:</strong> {@link #load(String, String)} returns the most recent
 * persisted state for the {@code (type, correlationKey)} pair, or {@link Optional#empty()} if the
 * saga has not been instantiated yet. {@link SagaRunner} uses this to decide between create-fresh
 * and resume-existing paths.
 *
 * <p><strong>Checkpoint preservation:</strong> The {@link SagaState#lastProcessedGlobalPosition()}
 * is the global event store position; this enables restart safety and idempotency.
 */
public interface SagaStorage {

    /** Look up the saga state for the given {@code (type, correlationKey)} pair. */
    Optional<SagaState> load(String type, String correlationKey);

    /**
     * Authoritative compare-and-set on saga ownership — the durable counterpart to the
     * ring's {@link net.nexus_flow.core.ring.saga.SagaLease} gossip. While the ring's
     * {@code SAGA_STATE} frames are HINTS that propagate observations across peers, this
     * method is the SOURCE OF TRUTH: the lease wire layer is only safe because every
     * ownership change is gated by a CAS through here.
     *
     * <p>Semantics:
     *
     * <ul>
     * <li>If the storage has no ownership row for {@code (sagaType, correlationKey)}
     * (saga unknown) the call returns
     * {@link OwnershipClaimResult.SagaUnknown}. The caller is expected to either
     * create the saga first via {@link #save(SagaState, long)} or wait for a fresh
     * observation from the ring.
     * <li>If the saga is unowned OR the current lease has expired at {@code now}, the
     * storage atomically writes {@code claimant} as the new owner with
     * {@code leaseExpiresAt = newLeaseExpiry} and increments the fencing token. The
     * call returns {@link OwnershipClaimResult.Acquired}.
     * <li>If the saga is owned by a different peer AND the lease is still valid, the
     * storage MUST NOT modify the row. The call returns
     * {@link OwnershipClaimResult.AlreadyHeldByOther} with the persisted ownership.
     * </ul>
     *
     * <p>The fencing token returned in {@link OwnershipClaimResult.Acquired#lease()} is
     * monotonically greater than every previously-issued token for this saga. Operators
     * forwarding side effects through external systems (e.g., Kafka with idempotent
     * producers) include the token so a stale owner that finishes its work AFTER handoff
     * cannot overwrite the new owner.
     *
     * <p><strong>Concurrency:</strong> implementations MUST be atomic — a JDBC backend
     * runs the read-modify-write inside one transaction with
     * {@code SELECT … FOR UPDATE} or an unconditional
     * {@code UPDATE … WHERE current_token = ?} predicate; the in-memory backend uses the
     * per-saga lock that already gates {@link #save}.
     *
     * <p><strong>Default implementation:</strong> throws {@link UnsupportedOperationException}.
     * Backends that ship before this method existed (legacy adapters) keep working as long
     * as they are NOT wired to a code path that calls it. The framework's ring integration
     * REQUIRES this method; adapter modules MUST override it before being wired into a ring
     * deployment.
     *
     * @param sagaType       the saga type discriminator
     * @param correlationKey the saga instance correlation key
     * @param claimant       the peer requesting ownership; must not be {@code null} or blank
     * @param newLeaseExpiry the wall-clock expiry the claimant wants on success
     * @param now            current wall-clock instant used to test the existing lease's expiry
     * @return the outcome; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    default OwnershipClaimResult tryAcquireOwnership(
            String sagaType, String correlationKey, String claimant,
            Instant newLeaseExpiry, Instant now) {
        throw new UnsupportedOperationException(
                getClass().getName()
                        + " does not implement tryAcquireOwnership(); ring saga leasing requires"
                        + " this method to be overridden.");
    }

    /**
     * Read the current ownership record without modifying anything. Returns
     * {@link Optional#empty()} when the saga is unknown OR when the persisted ownership is
     * {@link SagaOwnership#UNOWNED}. Used by health checks and the ring's gossip layer to
     * present a consistent view of who currently owns each saga.
     *
     * <p><strong>Default implementation:</strong> returns {@link Optional#empty()}.
     * Backends that implement {@link #tryAcquireOwnership} should also override this.
     */
    default Optional<SagaOwnership> loadOwnership(String sagaType, String correlationKey) {
        return Optional.empty();
    }

    /**
     * Subscribe to changes on this storage. The returned subscription is closed via
     * {@link SagaStorageObserver.Subscription#close()} to release resources.
     *
     * <p>Implementations that natively support push notifications (JDBC LISTEN/NOTIFY,
     * Redis Streams, etc.) deliver state changes inline; the in-memory backend invokes the
     * observer synchronously inside the save's critical section. Backends that do NOT
     * support push return a no-op subscription — callers degrade to polling.
     *
     * <p>{@link net.nexus_flow.core.saga.SagaCompletionAwaiter} uses this to wake on
     * terminal transitions without polling when the backend supports it.
     *
     * @param observer the listener; never {@code null}
     * @return a subscription that the caller closes when it no longer needs notifications
     */
    default SagaStorageObserver.Subscription subscribe(SagaStorageObserver observer) {
        return SagaStorageObserver.Subscription.NO_OP;
    }

    /**
     * Persist {@code state} asserting that the current persisted version equals {@code
     * expectedVersion}. This enforces the optimistic concurrency invariant.
     *
     * <p>Semantics:
     *
     * <ul>
     * <li>If no state is currently persisted for this {@code (type, correlationKey)} and {@code
     *       expectedVersion == 0}, the save succeeds (first write).
     * <li>If state exists and {@code expectedVersion == currentVersion}, the save succeeds and the
     * version is incremented.
     * <li>Otherwise, a {@link SagaConcurrencyException} is thrown.
     * </ul>
     *
     * @param state           the new state to persist
     * @param expectedVersion the version we expect to find (from {@link SagaState#version()} before
     *                        modification)
     * @throws SagaConcurrencyException if the persisted version does not match {@code
     *     expectedVersion}
     */
    void save(SagaState state, long expectedVersion);
}

package net.nexus_flow.core.saga;

import java.time.Instant;
import java.util.Objects;
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
     * Same CAS contract as {@link #tryAcquireOwnership(String, String, String, Instant, Instant)}
     * but keyed by the saga's stable {@link SagaId} instead of the {@code (sagaType,
     * correlationKey)} addressing tuple. The framework's ring layer ({@link
     * net.nexus_flow.core.ring.saga.SagaLeaseCoordinator}) tracks every owned lease by
     * {@code SagaId}; routing the claim through the id-keyed path lets the coordinator and the
     * storage agree on a single canonical key without forcing the coordinator to maintain a
     * shadow {@code (sagaType, correlationKey)} registry of its own.
     *
     * <p><strong>Indexing requirement:</strong> implementations MUST be able to resolve a
     * {@link SagaId} to its persisted state row. JDBC backends should add a {@code UNIQUE}
     * index on the {@code saga_id} column; the in-memory backend maintains a
     * {@link java.util.concurrent.ConcurrentHashMap}-based reverse index. The lookup happens
     * inside the CAS critical section, so it MUST be at most O(log n).
     *
     * <p><strong>Default implementation:</strong> throws {@link UnsupportedOperationException}.
     * Backends that ship before this method existed continue to compile; deployments that wire
     * such a backend into a ring-saga path will surface the absence at the first claim — fast
     * fail rather than a silently broken lease.
     *
     * <p>Semantics:
     *
     * <ul>
     * <li>{@link OwnershipClaimResult.SagaUnknown} when no saga state row matches {@code
     *       sagaId}.
     * <li>{@link OwnershipClaimResult.Acquired} when the storage CAS succeeds (unowned, lease
     * expired, or {@code claimant} already holds the lease — see the type-keyed
     * variant's contract).
     * <li>{@link OwnershipClaimResult.AlreadyHeldByOther} when an unexpired lease belongs to
     * a different peer.
     * </ul>
     *
     * @param sagaId         the stable saga identifier
     * @param claimant       the peer requesting ownership
     * @param newLeaseExpiry the wall-clock expiry the claimant wants on success
     * @param now            current wall-clock instant
     * @return the outcome; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    default OwnershipClaimResult tryAcquireOwnershipById(
            SagaId sagaId, String claimant, Instant newLeaseExpiry, Instant now) {
        throw new UnsupportedOperationException(
                getClass().getName()
                        + " does not implement tryAcquireOwnershipById(SagaId, ...); ring saga"
                        + " leasing requires this method to be overridden when leases are addressed"
                        + " by SagaId (the canonical key the SagaLeaseCoordinator uses).");
    }

    /**
     * Return up to {@code batchSize} sagas whose {@link SagaState#deadline()} is strictly
     * before {@code now} AND whose status is still {@link SagaStatus#RUNNING}. The
     * {@link SagaRunner}'s timeout sweeper invokes this periodically to drive expired sagas
     * to {@link SagaStatus#FAILED_TERMINAL} (or to compensation if the {@link Saga}
     * implementation opts in via a {@code onTimeout}-like hook in a future phase).
     *
     * <p>Implementations SHOULD index the {@code deadline} column for production performance:
     *
     * <ul>
     * <li>JDBC: {@code CREATE INDEX ... ON sagas (deadline) WHERE status = 'RUNNING'}.
     * <li>In-memory: a linear scan is acceptable up to ~100 k active sagas; the in-tree
     * implementation does exactly that.
     * </ul>
     *
     * <p><strong>Default implementation:</strong> returns an empty list. Backends that don't
     * yet support timeouts behave as if no saga ever expires — safe but means the runner's
     * sweeper finds nothing to do. Adapter modules that DO support timeouts override this
     * method.
     *
     * @param now       wall-clock instant against which {@code deadline} is compared
     * @param batchSize maximum number of sagas to return in one sweep; must be {@code >= 1}
     * @return list of running sagas whose deadline is in the past; never {@code null}, may be
     *         empty; sorted by ascending deadline (earliest-expired-first) so a starved sweeper
     *         drains the most overdue sagas first
     * @throws NullPointerException     if {@code now} is {@code null}
     * @throws IllegalArgumentException if {@code batchSize < 1}
     */
    default java.util.List<SagaState> findExpired(Instant now, int batchSize) {
        Objects.requireNonNull(now, "now");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1: " + batchSize);
        }
        return java.util.List.of();
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

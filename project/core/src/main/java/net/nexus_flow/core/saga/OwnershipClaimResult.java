package net.nexus_flow.core.saga;

import java.util.Objects;

/**
 * Outcome of a {@link SagaStorage#tryAcquireOwnership} call. Sealed sum type so the call
 * site pattern-matches the three possible outcomes exhaustively.
 */
public sealed interface OwnershipClaimResult
        permits OwnershipClaimResult.Acquired,
        OwnershipClaimResult.AlreadyHeldByOther,
        OwnershipClaimResult.SagaUnknown {

    /**
     * The storage CAS succeeded: {@code claimant} is now the owner and {@code lease} is the
     * canonical record. The {@code fencingToken} on the lease is monotonically greater than
     * any previously-issued token for this saga.
     */
    record Acquired(SagaOwnership lease) implements OwnershipClaimResult {
        public Acquired {
            Objects.requireNonNull(lease, "lease");
            if (lease.isUnowned()) {
                throw new IllegalArgumentException("Acquired lease must name an owner");
            }
        }
    }

    /**
     * The CAS failed because someone else holds an unexpired lease. {@code currentOwner} is
     * the persisted ownership record at the moment of the CAS — callers update their local
     * registry with this observation.
     */
    record AlreadyHeldByOther(SagaOwnership currentOwner) implements OwnershipClaimResult {
        public AlreadyHeldByOther {
            Objects.requireNonNull(currentOwner, "currentOwner");
            if (currentOwner.isUnowned()) {
                throw new IllegalArgumentException(
                        "AlreadyHeldByOther requires a non-unowned currentOwner");
            }
        }
    }

    /**
     * The storage has no record of this saga. Distinct from {@link AlreadyHeldByOther} so
     * callers can branch: cluster-wide membership gossip will surface the saga via {@link
     * net.nexus_flow.core.ring.wire.FrameType#SAGA_STATE}; the claimant should NOT keep
     * retrying blindly.
     */
    record SagaUnknown() implements OwnershipClaimResult {
        public static final SagaUnknown INSTANCE = new SagaUnknown();
    }
}

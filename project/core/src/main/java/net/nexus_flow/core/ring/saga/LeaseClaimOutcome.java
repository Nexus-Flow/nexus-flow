package net.nexus_flow.core.ring.saga;

import net.nexus_flow.core.ring.transport.PeerId;
import org.jspecify.annotations.Nullable;

/**
 * Result of a local attempt to claim a saga lease via {@link SagaOwnershipClaimer#tryClaim}.
 * Sealed sum type so callers pattern-match exhaustively over the three outcomes.
 */
public sealed interface LeaseClaimOutcome {

    /**
     * The claim succeeded — this peer is now the owner. The coordinator broadcasts a
     * {@link SagaStateEnvelope} to inform the rest of the ring.
     */
    record Claimed(SagaLease lease) implements LeaseClaimOutcome {
    }

    /**
     * The claim was rejected because another peer raced and won the same lease. The
     * {@code currentOwner} field communicates the winner so the coordinator updates its local
     * {@link LeaseRegistry} without waiting for an inbound SAGA_STATE.
     */
    record AlreadyOwned(PeerId currentOwner, @Nullable SagaLease currentLease)
            implements LeaseClaimOutcome {
    }

    /**
     * The claim could not proceed because the local storage does not have the saga at all (the
     * peer is observing leases for sagas it has never seen). The coordinator typically requests
     * a state sync from another peer before retrying.
     */
    record SagaUnknown() implements LeaseClaimOutcome {
    }
}

package net.nexus_flow.core.ring.saga;

import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.saga.SagaId;

/**
 * Pluggable single-writer CAS for saga lease ownership. The {@link SagaLeaseCoordinator}
 * delegates the actual storage transition to an implementation of this SPI — the framework
 * ships nothing in-core (production adapters wrap {@link
 * net.nexus_flow.core.saga.SagaStorage}; test fixtures use an in-memory CAS map).
 *
 * <h2>Single-writer invariant</h2>
 *
 * Implementations MUST guarantee that two concurrent {@link #tryClaim} calls for the same
 * saga from different peers cannot both return {@link LeaseClaimOutcome.Claimed}. The
 * canonical implementation uses {@link
 * net.nexus_flow.core.saga.SagaStorage}'s optimistic concurrency: read state with current
 * {@code version}, check ownership / expiry, write with {@code version + 1} predicated on
 * unchanged version. JDBC variants do the same via {@code UPDATE ... WHERE version = ?}.
 */
@FunctionalInterface
public interface SagaOwnershipClaimer {

    /**
     * Attempt to take ownership of {@code sagaId} for {@code newOwner} with the supplied lease
     * {@code expiry}. Returns the outcome — {@link LeaseClaimOutcome.Claimed} on success,
     * {@link LeaseClaimOutcome.AlreadyOwned} if another peer beat us to it, {@link
     * LeaseClaimOutcome.SagaUnknown} if the storage does not know the saga.
     */
    LeaseClaimOutcome tryClaim(SagaId sagaId, PeerId newOwner, java.time.Instant expiry);
}

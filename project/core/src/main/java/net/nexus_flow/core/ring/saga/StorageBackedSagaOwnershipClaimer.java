package net.nexus_flow.core.ring.saga;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.saga.OwnershipClaimResult;
import net.nexus_flow.core.saga.SagaId;
import net.nexus_flow.core.saga.SagaOwnership;
import net.nexus_flow.core.saga.SagaStorage;

/**
 * Production-grade {@link SagaOwnershipClaimer} that bridges the ring's {@link
 * SagaLeaseCoordinator} to the durable single-writer CAS in {@link SagaStorage}. This is the
 * adapter the framework wires by default when an application gives the ring runtime access to
 * its {@code SagaStorage} — every cross-pod lease transition then goes through the storage's
 * atomic compare-and-set instead of the in-memory test stub.
 *
 * <h2>Why a storage-backed claimer is mandatory in production</h2>
 *
 * The {@link SagaLeaseCoordinator} maintains a per-peer {@link LeaseRegistry} that is a HINT
 * about who currently owns each saga. Hints alone do not give single-writer guarantees — two
 * peers observing the same expired lease may BOTH believe the lease is fair game. Only an
 * atomic CAS through a durable backend can serialise the claim, and only the {@link
 * SagaStorage#tryAcquireOwnershipById} method (added so the ring layer can address sagas by
 * their stable {@link SagaId} rather than the {@code (type, correlationKey)} tuple the storage
 * uses internally) gives the lease coordinator a canonical key to claim by.
 *
 * <h2>Wire-to-storage semantics</h2>
 *
 * <table>
 * <caption>Outcome mapping</caption>
 * <tr><th>{@link OwnershipClaimResult}</th><th>{@link LeaseClaimOutcome}</th></tr>
 * <tr><td>{@code Acquired(lease)}</td>
 * <td>{@code Claimed(lease)}</td></tr>
 * <tr><td>{@code AlreadyHeldByOther(current)}</td>
 * <td>{@code AlreadyOwned(current.ownerPeerId, current.asLease)}</td></tr>
 * <tr><td>{@code SagaUnknown}</td>
 * <td>{@code SagaUnknown}</td></tr>
 * </table>
 *
 * <h2>Failure modes</h2>
 *
 * <ul>
 * <li>{@link UnsupportedOperationException} from the storage (legacy backend that has not
 * implemented {@link SagaStorage#tryAcquireOwnershipById}) is mapped to {@link
 * LeaseClaimOutcome.SagaUnknown} — the coordinator treats it as "cannot CAS through
 * storage, do nothing", which is safer than silently granting.
 * <li>Any other {@link RuntimeException} from the storage propagates to the caller. The
 * {@link SagaLeaseCoordinator}'s tick loop catches it and logs at {@code WARNING}; the
 * lease is retried on the next tick.
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * Stateless aside from the storage reference and clock. Concurrent calls compete only inside
 * the storage's own per-saga lock (in-memory) or row-level transaction (JDBC).
 */
public final class StorageBackedSagaOwnershipClaimer implements SagaOwnershipClaimer {

    private static final System.Logger LOG =
            System.getLogger(StorageBackedSagaOwnershipClaimer.class.getName());

    private final SagaStorage storage;
    private final Clock       clock;

    /**
     * @param storage the durable single-writer storage; must implement {@link
     *                SagaStorage#tryAcquireOwnershipById}. The in-tree {@link
     *                net.nexus_flow.core.saga.InMemorySagaStorage} satisfies this contract; adapter
     *                modules must override the by-id variant before being wired through this claimer.
     * @param clock   wall-clock source used to compute the {@code now} parameter of the CAS — pass
     *                the same {@link Clock} the {@link SagaLeaseCoordinator} uses so lease-expiry
     *                comparisons are consistent
     */
    public StorageBackedSagaOwnershipClaimer(SagaStorage storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock   = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public LeaseClaimOutcome tryClaim(SagaId sagaId, PeerId newOwner, Instant expiry) {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(newOwner, "newOwner");
        Objects.requireNonNull(expiry, "expiry");
        OwnershipClaimResult storageResult;
        try {
            storageResult =
                    storage.tryAcquireOwnershipById(sagaId, newOwner.value(), expiry, clock.instant());
        } catch (UnsupportedOperationException uoe) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "storage-backed claimer: SagaStorage "
                            + storage.getClass().getName()
                            + " does not implement tryAcquireOwnershipById — refusing claim for "
                            + sagaId + ". Wire a SagaStorage implementation that supports id-keyed"
                            + " CAS (InMemorySagaStorage or a production adapter) before"
                            + " enabling ring saga leasing.");
            return new LeaseClaimOutcome.SagaUnknown();
        }
        return switch (storageResult) {
            case OwnershipClaimResult.Acquired acquired       -> mapAcquired(sagaId, acquired);
            case OwnershipClaimResult.AlreadyHeldByOther held ->
                 mapAlreadyHeld(sagaId, held);
            case OwnershipClaimResult.SagaUnknown _           -> new LeaseClaimOutcome.SagaUnknown();
        };
    }

    private static LeaseClaimOutcome mapAcquired(SagaId sagaId, OwnershipClaimResult.Acquired acquired) {
        SagaOwnership lease    = acquired.lease();
        PeerId        ownerPid = PeerId.of(Objects.requireNonNull(lease.ownerPeerId()));
        Instant       expiry   = Objects.requireNonNull(lease.leaseExpiresAt());
        return new LeaseClaimOutcome.Claimed(new SagaLease(sagaId, ownerPid, expiry));
    }

    private static LeaseClaimOutcome mapAlreadyHeld(
            SagaId sagaId, OwnershipClaimResult.AlreadyHeldByOther held) {
        SagaOwnership current  = held.currentOwner();
        PeerId        ownerPid = PeerId.of(Objects.requireNonNull(current.ownerPeerId()));
        Instant       expiry   = Objects.requireNonNull(current.leaseExpiresAt());
        return new LeaseClaimOutcome.AlreadyOwned(
                ownerPid, new SagaLease(sagaId, ownerPid, expiry));
    }
}

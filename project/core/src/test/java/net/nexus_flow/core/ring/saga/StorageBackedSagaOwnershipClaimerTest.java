package net.nexus_flow.core.ring.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.saga.InMemorySagaStorage;
import net.nexus_flow.core.saga.SagaId;
import net.nexus_flow.core.saga.SagaState;
import net.nexus_flow.core.saga.SagaStatus;
import net.nexus_flow.core.saga.SagaStorage;
import org.junit.jupiter.api.Test;

/**
 * Pins the bridge between {@link SagaStorage#tryAcquireOwnershipById} and the ring's
 * {@link SagaOwnershipClaimer} SPI. The mapping MUST be lossless across all three storage
 * outcomes, and MUST refuse to grant claims when the storage cannot perform an id-keyed CAS.
 */
class StorageBackedSagaOwnershipClaimerTest {

    private static final PeerId POD_A = PeerId.of("pod-a");
    private static final PeerId POD_B = PeerId.of("pod-b");
    private static final Clock  FIXED = Clock.fixed(
                                                    Instant.parse("2026-05-27T12:00:00Z"), ZoneOffset.UTC);

    private static SagaId seedSaga(InMemorySagaStorage storage, String type, String key) {
        SagaId    id   = new SagaId(UUID.randomUUID());
        SagaState seed = SagaState.fresh(id, type, FIXED.instant())
                .next(Map.of("_correlationKey", key), SagaStatus.RUNNING, 0L, FIXED.instant());
        storage.save(seed, 0L);
        return id;
    }

    @Test
    void claim_succeeds_whenStorageAcquires() {
        InMemorySagaStorage               storage = new InMemorySagaStorage();
        SagaId                            id      = seedSaga(storage, "Order", "o-1");
        StorageBackedSagaOwnershipClaimer claimer = new StorageBackedSagaOwnershipClaimer(storage, FIXED);
        LeaseClaimOutcome                 outcome = claimer.tryClaim(id, POD_A, FIXED.instant().plusSeconds(30));
        LeaseClaimOutcome.Claimed         claimed = assertInstanceOf(LeaseClaimOutcome.Claimed.class, outcome);
        assertEquals(POD_A, claimed.lease().ownerPeerId());
        assertEquals(id, claimed.lease().sagaId());
    }

    @Test
    void claim_alreadyOwned_mapsToAlreadyOwned() {
        InMemorySagaStorage               storage = new InMemorySagaStorage();
        SagaId                            id      = seedSaga(storage, "Order", "o-1");
        StorageBackedSagaOwnershipClaimer claimer = new StorageBackedSagaOwnershipClaimer(storage, FIXED);
        // First peer takes the lease.
        claimer.tryClaim(id, POD_A, FIXED.instant().plusSeconds(60));
        // Second peer is rejected.
        LeaseClaimOutcome              second = claimer.tryClaim(id, POD_B, FIXED.instant().plusSeconds(60));
        LeaseClaimOutcome.AlreadyOwned ao     = assertInstanceOf(LeaseClaimOutcome.AlreadyOwned.class, second);
        assertEquals(POD_A, ao.currentOwner());
    }

    @Test
    void claim_unknownSaga_mapsToSagaUnknown() {
        InMemorySagaStorage               storage = new InMemorySagaStorage();
        StorageBackedSagaOwnershipClaimer claimer = new StorageBackedSagaOwnershipClaimer(storage, FIXED);
        LeaseClaimOutcome                 outcome = claimer.tryClaim(
                                                                     new SagaId(UUID.randomUUID()), POD_A, FIXED.instant().plusSeconds(30));
        assertInstanceOf(LeaseClaimOutcome.SagaUnknown.class, outcome);
    }

    @Test
    void claim_storageWithoutByIdSupport_refusesClaim() {
        // Legacy storage that has not overridden tryAcquireOwnershipById — the claimer MUST
        // fail safe by mapping the UOE to SagaUnknown rather than silently granting.
        SagaStorage                       legacyStorage = new SagaStorage() {
                                                            @Override
                                                            public Optional<SagaState> load(String type, String key) {
                                                                return Optional.empty();
                                                            }

                                                            @Override
                                                            public void save(SagaState state, long expectedVersion) {
                                                                // no-op
                                                            }
                                                        };
        StorageBackedSagaOwnershipClaimer claimer       = new StorageBackedSagaOwnershipClaimer(legacyStorage, FIXED);
        LeaseClaimOutcome                 outcome       = claimer.tryClaim(
                                                                           new SagaId(UUID.randomUUID()), POD_A, FIXED.instant()
                                                                                   .plusSeconds(30));
        assertInstanceOf(LeaseClaimOutcome.SagaUnknown.class, outcome,
                         "missing id-keyed CAS support MUST surface as SagaUnknown — never silent grant");
    }

    @Test
    void nullArgs_rejected() {
        InMemorySagaStorage               storage = new InMemorySagaStorage();
        StorageBackedSagaOwnershipClaimer claimer = new StorageBackedSagaOwnershipClaimer(storage, FIXED);
        SagaId                            id      = new SagaId(UUID.randomUUID());
        assertThrows(NullPointerException.class,
                     () -> claimer.tryClaim(null, POD_A, FIXED.instant().plusSeconds(10)));
        assertThrows(NullPointerException.class,
                     () -> claimer.tryClaim(id, null, FIXED.instant().plusSeconds(10)));
        assertThrows(NullPointerException.class,
                     () -> claimer.tryClaim(id, POD_A, null));
        assertThrows(NullPointerException.class, () -> new StorageBackedSagaOwnershipClaimer(null, FIXED));
        assertThrows(NullPointerException.class, () -> new StorageBackedSagaOwnershipClaimer(storage, null));
    }
}

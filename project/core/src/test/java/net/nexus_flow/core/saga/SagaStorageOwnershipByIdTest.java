package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the id-keyed CAS path on {@link InMemorySagaStorage#tryAcquireOwnershipById}. The
 * reverse index from {@link SagaId} to {@code (type, correlationKey)} MUST be populated on save
 * and MUST agree with the type-keyed path under concurrent claims.
 */
class SagaStorageOwnershipByIdTest {

    private static SagaState seedSaga(InMemorySagaStorage storage, String type, String key, SagaId id) {
        SagaState seed = SagaState.fresh(id, type, Instant.now())
                .next(Map.of("_correlationKey", key), SagaStatus.RUNNING, 0L, Instant.now());
        storage.save(seed, 0L);
        return seed;
    }

    @Test
    void byId_acquireOnUnowned_succeeds() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        SagaId              id      = new SagaId(UUID.randomUUID());
        seedSaga(storage, "Order", "o-1", id);
        Instant              now    = Instant.now();
        OwnershipClaimResult result = storage.tryAcquireOwnershipById(
                                                                      id, "pod-a", now.plusSeconds(30), now);
        assertInstanceOf(OwnershipClaimResult.Acquired.class, result);
        assertEquals("pod-a",
                     ((OwnershipClaimResult.Acquired) result).lease().ownerPeerId());
    }

    @Test
    void byId_unknownSaga_returnsSagaUnknown() {
        InMemorySagaStorage  storage = new InMemorySagaStorage();
        SagaId               unknown = new SagaId(UUID.randomUUID());
        OwnershipClaimResult result  = storage.tryAcquireOwnershipById(
                                                                       unknown, "pod-a", Instant.now().plusSeconds(10), Instant.now());
        assertEquals(OwnershipClaimResult.SagaUnknown.INSTANCE, result);
    }

    @Test
    void byId_agreesWithByType_whenBothCallAtTheSameInstant() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        SagaId              id      = new SagaId(UUID.randomUUID());
        seedSaga(storage, "Order", "o-1", id);
        Instant              t0      = Instant.now();
        OwnershipClaimResult byIdRes = storage.tryAcquireOwnershipById(
                                                                       id, "pod-a", t0.plusSeconds(30), t0);
        assertInstanceOf(OwnershipClaimResult.Acquired.class, byIdRes);
        // A by-type claim by another pod at the same time must observe the lease taken via
        // the id path — same per-saga lock, same fencing-token monotonicity.
        OwnershipClaimResult byTypeRes = storage.tryAcquireOwnership(
                                                                     "Order", "o-1", "pod-b", t0.plusSeconds(30), t0);
        assertInstanceOf(OwnershipClaimResult.AlreadyHeldByOther.class, byTypeRes);
    }

    @Test
    void byId_renewSameOwner_bumpsFencingToken() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        SagaId              id      = new SagaId(UUID.randomUUID());
        seedSaga(storage, "Order", "o-1", id);
        Instant t0 = Instant.now();
        storage.tryAcquireOwnershipById(id, "pod-a", t0.plusSeconds(10), t0);

        OwnershipClaimResult renewal             = storage.tryAcquireOwnershipById(
                                                                                   id, "pod-a", t0.plusSeconds(30), t0.plusSeconds(5));
        long                 fencingAfterRenewal =
                ((OwnershipClaimResult.Acquired) renewal).lease().fencingToken();
        assertTrue(fencingAfterRenewal >= 2L,
                   "renewal by same owner MUST bump fencing token monotonically");
    }

    @Test
    void byId_nullArgs_rejected() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        SagaId              id      = new SagaId(UUID.randomUUID());
        Instant             now     = Instant.now();
        assertThrows(NullPointerException.class,
                     () -> storage.tryAcquireOwnershipById(null, "pod", now.plusSeconds(10), now));
        assertThrows(NullPointerException.class,
                     () -> storage.tryAcquireOwnershipById(id, null, now.plusSeconds(10), now));
        assertThrows(NullPointerException.class,
                     () -> storage.tryAcquireOwnershipById(id, "pod", null, now));
        assertThrows(NullPointerException.class,
                     () -> storage.tryAcquireOwnershipById(id, "pod", now.plusSeconds(10), null));
    }

    @Test
    void byId_defaultMethodOnUnrelatedStorage_throwsUOE() {
        SagaStorage stub = new SagaStorage() {
                             @Override
                             public java.util.Optional<SagaState> load(String type, String correlationKey) {
                                 return java.util.Optional.empty();
                             }

                             @Override
                             public void save(SagaState state, long expectedVersion) {
                                 // no-op
                             }
                         };
        SagaId      id   = new SagaId(UUID.randomUUID());
        assertThrows(UnsupportedOperationException.class,
                     () -> stub.tryAcquireOwnershipById(
                                                        id, "pod-a", Instant.now().plusSeconds(10), Instant.now()));
    }
}

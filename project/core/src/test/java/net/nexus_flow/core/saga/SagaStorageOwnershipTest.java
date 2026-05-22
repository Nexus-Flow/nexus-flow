package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SagaStorageOwnershipTest {

    private static SagaState seedSaga(InMemorySagaStorage storage, String type, String key) {
        SagaState seed = SagaState.fresh(new SagaId(UUID.randomUUID()), type, Instant.now())
                .next(Map.of("_correlationKey", key), SagaStatus.RUNNING, 0L, Instant.now());
        storage.save(seed, 0L);
        return seed;
    }

    @Test
    void tryAcquireOwnership_unowned_succeeds() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        seedSaga(storage, "Order", "o-1");
        Instant              now    = Instant.now();
        OwnershipClaimResult result = storage.tryAcquireOwnership(
                                                                  "Order", "o-1", "pod-a", now.plusSeconds(30), now);
        assertInstanceOf(OwnershipClaimResult.Acquired.class, result);
        SagaOwnership lease = ((OwnershipClaimResult.Acquired) result).lease();
        assertEquals("pod-a", lease.ownerPeerId());
        assertEquals(1L, lease.fencingToken(),
                     "first acquire MUST yield fencing token 1 (incremented from 0)");
    }

    @Test
    void tryAcquireOwnership_alreadyHeldByDifferentPeer_fails() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        seedSaga(storage, "Order", "o-1");
        Instant now = Instant.now();
        storage.tryAcquireOwnership("Order", "o-1", "pod-a", now.plusSeconds(60), now);

        OwnershipClaimResult result = storage.tryAcquireOwnership(
                                                                  "Order", "o-1", "pod-b", now.plusSeconds(60), now);
        assertInstanceOf(OwnershipClaimResult.AlreadyHeldByOther.class, result);
        assertEquals("pod-a",
                     ((OwnershipClaimResult.AlreadyHeldByOther) result).currentOwner().ownerPeerId());
    }

    @Test
    void tryAcquireOwnership_expiredLease_canBeTakenByDifferentPeer() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        seedSaga(storage, "Order", "o-1");
        Instant t0 = Instant.now();
        storage.tryAcquireOwnership("Order", "o-1", "pod-a", t0.plusSeconds(10), t0);

        Instant              tLater = t0.plusSeconds(30); // expired
        OwnershipClaimResult result = storage.tryAcquireOwnership(
                                                                  "Order", "o-1", "pod-b", tLater.plusSeconds(10), tLater);
        assertInstanceOf(OwnershipClaimResult.Acquired.class, result);
        SagaOwnership lease = ((OwnershipClaimResult.Acquired) result).lease();
        assertEquals("pod-b", lease.ownerPeerId());
        assertEquals(2L, lease.fencingToken(),
                     "every successful claim MUST increment the fencing token");
    }

    @Test
    void tryAcquireOwnership_sameOwnerRenewing_succeeds_andBumpsFencingToken() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        seedSaga(storage, "Order", "o-1");
        Instant t0 = Instant.now();
        storage.tryAcquireOwnership("Order", "o-1", "pod-a", t0.plusSeconds(10), t0);

        OwnershipClaimResult renewal = storage.tryAcquireOwnership(
                                                                   "Order", "o-1", "pod-a", t0.plusSeconds(30), t0.plusSeconds(5));
        assertInstanceOf(OwnershipClaimResult.Acquired.class, renewal);
        assertEquals(2L, ((OwnershipClaimResult.Acquired) renewal).lease().fencingToken());
    }

    @Test
    void tryAcquireOwnership_unknownSaga_returnsSagaUnknown() {
        InMemorySagaStorage  storage = new InMemorySagaStorage();
        OwnershipClaimResult result  = storage.tryAcquireOwnership(
                                                                   "Order", "ghost", "pod-a", Instant.now().plusSeconds(10), Instant.now());
        assertEquals(OwnershipClaimResult.SagaUnknown.INSTANCE, result);
    }

    @Test
    void loadOwnership_returnsEmpty_whenUnowned() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        seedSaga(storage, "Order", "o-1");
        assertTrue(storage.loadOwnership("Order", "o-1").isEmpty());
    }

    @Test
    void loadOwnership_returnsLease_afterAcquire() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        seedSaga(storage, "Order", "o-1");
        Instant now = Instant.now();
        storage.tryAcquireOwnership("Order", "o-1", "pod-a", now.plusSeconds(30), now);

        Optional<SagaOwnership> loaded = storage.loadOwnership("Order", "o-1");
        assertTrue(loaded.isPresent());
        assertEquals("pod-a", loaded.get().ownerPeerId());
    }

    @Test
    void subscribe_invokesObserverOnEverySave_synchronously() {
        InMemorySagaStorage        storage  = new InMemorySagaStorage();
        AtomicReference<SagaState> lastSeen = new AtomicReference<>();
        try (SagaStorageObserver.Subscription _ = storage.subscribe(
                                                                    (type, key, state) -> lastSeen.set(state))) {
            SagaState saved = seedSaga(storage, "Order", "o-1");
            assertNotNull(lastSeen.get(), "observer must see the save synchronously");
            assertEquals(saved.id(), lastSeen.get().id());
        }
    }

    @Test
    void subscribe_close_stopsFurtherNotifications() {
        InMemorySagaStorage              storage = new InMemorySagaStorage();
        AtomicReference<SagaState>       seen    = new AtomicReference<>();
        SagaStorageObserver.Subscription sub     = storage.subscribe(
                                                                     (type, key, state) -> seen.set(state));
        sub.close();
        seedSaga(storage, "Order", "o-1");
        assertEquals(null, seen.get(),
                     "observer MUST NOT fire after close");
    }

    @Test
    void tryAcquireOwnership_validation_rejectsBlankClaimant() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        seedSaga(storage, "Order", "o-1");
        assertThrows(IllegalArgumentException.class,
                     () -> storage.tryAcquireOwnership(
                                                       "Order", "o-1", "  ",
                                                       Instant.now().plusSeconds(10), Instant.now()));
    }

    @Test
    void sagaOwnership_unownedSentinel_isExpiredAlways() {
        assertFalse(SagaOwnership.UNOWNED.isExpired(Instant.now()),
                    "UNOWNED has no lease — isExpired returns false");
        assertTrue(SagaOwnership.UNOWNED.isUnowned());
    }
}

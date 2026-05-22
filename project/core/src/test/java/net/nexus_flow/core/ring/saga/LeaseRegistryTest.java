package net.nexus_flow.core.ring.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.saga.SagaId;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link LeaseRegistry} observation + expiry semantics. The registry is the local cache
 * driving "should I attempt to claim this lease right now" decisions; correctness here is
 * what prevents both lost ownership (failed expiry detection) and split-brain (premature
 * claim while owner is still alive).
 */
class LeaseRegistryTest {

    private static final Clock T0 =
            Clock.fixed(Instant.parse("2026-05-25T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void observe_recordsLease_lookupReturnsIt() {
        LeaseRegistry reg    = new LeaseRegistry(T0);
        SagaId        sagaId = SagaId.random();
        SagaLease     lease  =
                SagaLease.owned(sagaId, PeerId.of("a"), T0, Duration.ofSeconds(30));
        reg.observe(lease);
        assertEquals(lease, reg.lease(sagaId).orElseThrow());
    }

    @Test
    void observe_newerExpiryWins_oldFrameDoesNotRegress() {
        LeaseRegistry reg    = new LeaseRegistry(T0);
        SagaId        sagaId = SagaId.random();
        SagaLease     newer  =
                SagaLease.owned(sagaId, PeerId.of("a"), T0, Duration.ofSeconds(60));
        SagaLease     older  =
                SagaLease.owned(sagaId, PeerId.of("a"), T0, Duration.ofSeconds(30));
        reg.observe(newer);
        reg.observe(older); // out-of-order frame with older expiry
        assertEquals(
                     newer.expiresAt(),
                     reg.lease(sagaId).orElseThrow().expiresAt(),
                     "older frame must NOT regress a newer observation");
    }

    @Test
    void observe_changingOwner_isHonoredIfExpiryAdvances() {
        LeaseRegistry reg    = new LeaseRegistry(T0);
        SagaId        sagaId = SagaId.random();
        SagaLease     byA    = SagaLease.owned(sagaId, PeerId.of("a"), T0, Duration.ofSeconds(30));
        SagaLease     byB    = SagaLease.owned(sagaId, PeerId.of("b"), T0, Duration.ofSeconds(60));
        reg.observe(byA);
        reg.observe(byB);
        assertEquals(PeerId.of("b"), reg.lease(sagaId).orElseThrow().ownerPeerId());
    }

    @Test
    void expiredLeases_returnsOnlyExpiredEntries_relativeToInjectedClock() {
        LeaseRegistry reg   = new LeaseRegistry(T0);
        SagaId        fresh = SagaId.random();
        SagaId        stale = SagaId.random();
        // Fresh: expires in 60s
        reg.observe(SagaLease.owned(fresh, PeerId.of("a"), T0, Duration.ofSeconds(60)));
        // Stale: already expired (expiresAt < T0.instant())
        reg.observe(
                    new SagaLease(
                            stale,
                            PeerId.of("a"),
                            Instant.parse("2026-05-25T09:59:00Z")));
        List<SagaLease> expired = reg.expiredLeases();
        assertEquals(1, expired.size());
        assertEquals(stale, expired.getFirst().sagaId());
    }

    @Test
    void ownedBy_filtersByOwnerPeerId() {
        LeaseRegistry reg = new LeaseRegistry(T0);
        reg.observe(SagaLease.owned(SagaId.random(), PeerId.of("a"), T0, Duration.ofSeconds(60)));
        reg.observe(SagaLease.owned(SagaId.random(), PeerId.of("a"), T0, Duration.ofSeconds(60)));
        reg.observe(SagaLease.owned(SagaId.random(), PeerId.of("b"), T0, Duration.ofSeconds(60)));
        assertEquals(2, reg.ownedBy(PeerId.of("a")).size());
        assertEquals(1, reg.ownedBy(PeerId.of("b")).size());
        assertEquals(0, reg.ownedBy(PeerId.of("c")).size());
    }

    @Test
    void forget_dropsTheEntry() {
        LeaseRegistry reg    = new LeaseRegistry(T0);
        SagaId        sagaId = SagaId.random();
        reg.observe(SagaLease.owned(sagaId, PeerId.of("a"), T0, Duration.ofSeconds(60)));
        reg.forget(sagaId);
        assertTrue(reg.lease(sagaId).isEmpty());
    }

    @Test
    void sagaLease_isExpired_isExclusiveOnExpiresAt() {
        SagaLease lease =
                new SagaLease(
                        SagaId.random(),
                        PeerId.of("a"),
                        Instant.parse("2026-05-25T10:00:00Z"));
        // Strictly before -> NOT expired.
        assertFalse(lease.isExpired(Instant.parse("2026-05-25T09:59:59Z")));
        // At exactly expiresAt -> expired (the boundary itself is treated as expired so a
        // peer that observes the deadline can immediately attempt the claim without waiting
        // an extra tick).
        assertTrue(lease.isExpired(Instant.parse("2026-05-25T10:00:00Z")));
        // After expiresAt -> expired.
        assertTrue(lease.isExpired(Instant.parse("2026-05-25T10:00:01Z")));
    }
}

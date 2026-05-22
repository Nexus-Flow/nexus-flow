package net.nexus_flow.core.ring.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ring.membership.DefaultMembershipRegistry;
import net.nexus_flow.core.ring.membership.PeerState;
import net.nexus_flow.core.ring.membership.StaticPeerListMembership;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.saga.SagaId;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link SagaLeaseCoordinator} renewal + claim + membership-reaction logic. Drives
 * {@link SagaLeaseCoordinator#tickOnce()} deterministically with a tickable clock and a fake
 * {@link SagaOwnershipClaimer} that simulates either SUCCESS or LOST-RACE outcomes.
 */
class SagaLeaseCoordinatorTest {

    private static final PeerId LOCAL  = PeerId.of("local");
    private static final PeerId REMOTE = PeerId.of("remote");

    /** Mutable clock for deterministic tests. */
    private static final class TickableClock extends Clock {
        Instant now = Instant.parse("2026-05-25T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    /** Fake claimer with configurable outcomes per saga. */
    private static final class FakeClaimer implements SagaOwnershipClaimer {
        final ConcurrentHashMap<SagaId, LeaseClaimOutcome> outcomes      = new ConcurrentHashMap<>();
        final AtomicInteger                                claimAttempts = new AtomicInteger();

        @Override
        public LeaseClaimOutcome tryClaim(SagaId sagaId, PeerId newOwner, Instant expiry) {
            claimAttempts.incrementAndGet();
            LeaseClaimOutcome configured = outcomes.get(sagaId);
            if (configured != null) {
                return configured;
            }
            return new LeaseClaimOutcome.Claimed(new SagaLease(sagaId, newOwner, expiry));
        }
    }

    /**
     * Stub connection with no peerId bound — the SagaLeaseCoordinator bypasses the
     * sender-owner check when the connection is unauthenticated (plain-TCP test path).
     */
    private static net.nexus_flow.core.ring.transport.RingConnection stubConn() {
        try {
            return net.nexus_flow.core.ring.transport.TestRingConnections.stub();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static SagaLeaseCoordinatorConfig fastConfig(PeerId local) {
        return new SagaLeaseCoordinatorConfig(
                local,
                Duration.ofSeconds(10),
                Duration.ofSeconds(3),
                Duration.ofMillis(500),
                Duration.ofSeconds(1));
    }

    private static StaticPeerListMembership membership() {
        Map<PeerId, PeerAddress> peers = new LinkedHashMap<>();
        peers.put(REMOTE, PeerAddress.loopback(9001));
        StaticPeerListMembership m = new StaticPeerListMembership(Clock.systemUTC(), peers);
        m.start();
        return m;
    }

    @Test
    void trackOwned_addsSagaToOwnedSet_andLeaseRegistry() {
        StaticPeerListMembership m           = membership();
        LeaseRegistry            registry    = new LeaseRegistry(Clock.systemUTC());
        RingConnectionRegistry   connections = new RingConnectionRegistry();
        FakeClaimer              claimer     = new FakeClaimer();
        SagaLeaseCoordinator     coord       =
                new SagaLeaseCoordinator(
                        fastConfig(LOCAL),
                        Clock.systemUTC(),
                        registry,
                        connections,
                        claimer,
                        m.registry());
        SagaId                   sagaId      = SagaId.random();
        SagaLease                lease       =
                new SagaLease(sagaId, LOCAL, Instant.now().plus(Duration.ofMinutes(1)));
        coord.trackOwned(lease);
        assertEquals(1, coord.ownedSagas().size());
        assertEquals(lease, registry.lease(sagaId).orElseThrow());
    }

    @Test
    void trackOwned_rejectsLeaseOwnedByOtherPeer() {
        StaticPeerListMembership m     = membership();
        SagaLeaseCoordinator     coord = newCoord(m, new FakeClaimer());
        SagaLease                lease =
                new SagaLease(SagaId.random(), REMOTE, Instant.now().plus(Duration.ofMinutes(1)));
        assertThrowsIllegalArgument(() -> coord.trackOwned(lease));
    }

    @Test
    void releaseOwned_dropsFromOwnedAndRegistry() {
        StaticPeerListMembership m        = membership();
        LeaseRegistry            registry = new LeaseRegistry(Clock.systemUTC());
        SagaLeaseCoordinator     coord    = newCoord(m, registry, new FakeClaimer());
        SagaId                   sagaId   = SagaId.random();
        coord.trackOwned(new SagaLease(sagaId, LOCAL, Instant.now().plus(Duration.ofMinutes(1))));
        coord.releaseOwned(sagaId);
        assertTrue(coord.ownedSagas().isEmpty());
        assertTrue(registry.lease(sagaId).isEmpty());
    }

    @Test
    void onSagaState_recordsObservation_inLeaseRegistry() {
        StaticPeerListMembership m        = membership();
        LeaseRegistry            registry = new LeaseRegistry(Clock.systemUTC());
        SagaLeaseCoordinator     coord    = newCoord(m, registry, new FakeClaimer());
        SagaId                   sagaId   = SagaId.random();
        coord.onSagaState(stubConn(),
                          new SagaStateEnvelope(
                                  sagaId, REMOTE, Instant.now().plus(Duration.ofMinutes(1)).toEpochMilli(), 5L));
        SagaLease observed = registry.lease(sagaId).orElseThrow();
        assertEquals(REMOTE, observed.ownerPeerId());
    }

    @Test
    void onSagaState_announcingDifferentOwner_dropsLocalTrackingIfWeWereOwner() {
        StaticPeerListMembership m        = membership();
        LeaseRegistry            registry = new LeaseRegistry(Clock.systemUTC());
        SagaLeaseCoordinator     coord    = newCoord(m, registry, new FakeClaimer());
        SagaId                   sagaId   = SagaId.random();
        coord.trackOwned(new SagaLease(sagaId, LOCAL, Instant.now().plus(Duration.ofMinutes(1))));
        assertEquals(1, coord.ownedSagas().size());
        coord.onSagaState(stubConn(),
                          new SagaStateEnvelope(
                                  sagaId, REMOTE, Instant.now().plus(Duration.ofMinutes(2)).toEpochMilli(), 6L));
        assertTrue(coord.ownedSagas().isEmpty(),
                   "another peer announcing ownership must drop our local tracking");
    }

    @Test
    void tickOnce_renewsOwnedLeases_andBumpsExpiry() {
        TickableClock            clock    = new TickableClock();
        StaticPeerListMembership m        = membership();
        LeaseRegistry            registry = new LeaseRegistry(clock);
        SagaLeaseCoordinator     coord    =
                new SagaLeaseCoordinator(
                        new SagaLeaseCoordinatorConfig(
                                LOCAL,
                                Duration.ofSeconds(10),
                                Duration.ofMillis(1),
                                Duration.ofMillis(1),
                                Duration.ofSeconds(1)),
                        clock,
                        registry,
                        new RingConnectionRegistry(),
                        new FakeClaimer(),
                        m.registry());
        SagaId                   sagaId   = SagaId.random();
        SagaLease                initial  = new SagaLease(sagaId, LOCAL, clock.now.plus(Duration.ofSeconds(5)));
        coord.trackOwned(initial);
        clock.advance(Duration.ofSeconds(1));
        coord.tickOnce();
        SagaLease renewed = registry.lease(sagaId).orElseThrow();
        assertTrue(renewed.expiresAt().isAfter(initial.expiresAt()),
                   "tick must renew the lease forward in time");
    }

    @Test
    void scanExpired_attemptsClaim_andBroadcastsOnSuccess() {
        TickableClock            clock    = new TickableClock();
        StaticPeerListMembership m        = membership();
        LeaseRegistry            registry = new LeaseRegistry(clock);
        FakeClaimer              claimer  = new FakeClaimer();
        SagaLeaseCoordinator     coord    = newCoord(m, registry, claimer, clock);
        SagaId                   sagaId   = SagaId.random();
        // Insert a lease in registry that is already expired against the clock.
        registry.observe(new SagaLease(sagaId, REMOTE, clock.now.minus(Duration.ofSeconds(1))));
        coord.tickOnce();
        assertTrue(claimer.claimAttempts.get() >= 1, "expired lease must trigger a claim attempt");
        // FakeClaimer returns Claimed by default — coord should now own it.
        assertEquals(1, coord.ownedSagas().size());
        assertEquals(LOCAL, coord.ownedSagas().iterator().next().ownerPeerId());
    }

    @Test
    void scanExpired_skipsLocallyOwnedLeases() {
        TickableClock            clock    = new TickableClock();
        StaticPeerListMembership m        = membership();
        LeaseRegistry            registry = new LeaseRegistry(clock);
        FakeClaimer              claimer  = new FakeClaimer();
        SagaLeaseCoordinator     coord    = newCoord(m, registry, claimer, clock);
        SagaId                   sagaId   = SagaId.random();
        // Own a saga but the registry entry is expired.
        coord.trackOwned(new SagaLease(sagaId, LOCAL, clock.now.minus(Duration.ofSeconds(1))));
        coord.tickOnce();
        // The expired-claim scan should NOT have attempted to re-claim our own lease.
        // (The renewal path covers it instead.)
        assertEquals(
                     0,
                     claimer.claimAttempts.get(),
                     "scan must skip locally-owned leases — renewal path handles them");
    }

    @Test
    void peerLeftEvent_triggersImmediateClaimScan() {
        TickableClock             clock    = new TickableClock();
        StaticPeerListMembership  m        = membership();
        LeaseRegistry             registry = new LeaseRegistry(clock);
        FakeClaimer               claimer  = new FakeClaimer();
        DefaultMembershipRegistry mutable  = m.mutableRegistry();
        mutable.transition(REMOTE, PeerState.ALIVE);
        // Pre-seed an expired lease owned by REMOTE.
        SagaId sagaId = SagaId.random();
        registry.observe(new SagaLease(sagaId, REMOTE, clock.now.minus(Duration.ofSeconds(1))));
        SagaLeaseCoordinator coord          = newCoord(m, registry, claimer, clock);
        int                  beforeAttempts = claimer.claimAttempts.get();
        mutable.transition(REMOTE, PeerState.CONFIRMED_DEAD);
        org.awaitility.Awaitility.await()
                .atMost(2, java.util.concurrent.TimeUnit.SECONDS)
                .until(() -> claimer.claimAttempts.get() > beforeAttempts);
        org.awaitility.Awaitility.await()
                .atMost(2, java.util.concurrent.TimeUnit.SECONDS)
                .until(() -> coord.ownedSagas().size() == 1);
        assertTrue(claimer.claimAttempts.get() > beforeAttempts,
                   "peer-left event must trigger at least one claim attempt");
        assertEquals(1, coord.ownedSagas().size(),
                     "saga must be re-claimed by the surviving pod after CONFIRMED_DEAD");
    }

    @Test
    void scanExpired_lostRace_updatesRegistryWithNewOwnerFromClaimer() {
        TickableClock            clock          = new TickableClock();
        StaticPeerListMembership m              = membership();
        LeaseRegistry            registry       = new LeaseRegistry(clock);
        FakeClaimer              claimer        = new FakeClaimer();
        SagaId                   sagaId         = SagaId.random();
        Instant                  newOwnerExpiry = clock.now.plus(Duration.ofMinutes(1));
        claimer.outcomes.put(
                             sagaId,
                             new LeaseClaimOutcome.AlreadyOwned(
                                     REMOTE, new SagaLease(sagaId, REMOTE, newOwnerExpiry)));
        SagaLeaseCoordinator coord = newCoord(m, registry, claimer, clock);
        registry.observe(new SagaLease(sagaId, PeerId.of("c"), clock.now.minus(Duration.ofSeconds(1))));
        coord.tickOnce();
        SagaLease observed = registry.lease(sagaId).orElseThrow();
        assertEquals(REMOTE, observed.ownerPeerId(), "lost race must update registry with winner");
        assertEquals(newOwnerExpiry, observed.expiresAt());
    }

    @Test
    void config_renewalInterval_mustBeLessThanLeaseTtl() {
        assertThrowsIllegalArgument(() -> new SagaLeaseCoordinatorConfig(
                LOCAL,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5), // == ttl — must be < ttl
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)));
        assertThrowsIllegalArgument(() -> new SagaLeaseCoordinatorConfig(
                LOCAL,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10), // > ttl — invalid
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)));
    }

    // ---------- helpers ----------

    private static void assertThrowsIllegalArgument(Runnable r) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, r::run);
    }

    private static SagaLeaseCoordinator newCoord(StaticPeerListMembership m, FakeClaimer claimer) {
        return newCoord(m, new LeaseRegistry(Clock.systemUTC()), claimer, Clock.systemUTC());
    }

    private static SagaLeaseCoordinator newCoord(
            StaticPeerListMembership m, LeaseRegistry registry, FakeClaimer claimer) {
        return newCoord(m, registry, claimer, Clock.systemUTC());
    }

    private static SagaLeaseCoordinator newCoord(
            StaticPeerListMembership m, LeaseRegistry registry, FakeClaimer claimer, Clock clock) {
        return new SagaLeaseCoordinator(
                fastConfig(LOCAL),
                clock,
                registry,
                new RingConnectionRegistry(),
                claimer,
                m.registry());
    }
}

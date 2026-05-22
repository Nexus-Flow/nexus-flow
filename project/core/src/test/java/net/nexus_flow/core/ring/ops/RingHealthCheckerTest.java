package net.nexus_flow.core.ring.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import net.nexus_flow.core.ring.membership.PeerState;
import net.nexus_flow.core.ring.membership.StaticPeerListMembership;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link RingHealthChecker} composition policy: when does the aggregate level flip
 * from UP to DEGRADED, and when from DEGRADED to DOWN. The thresholds + classification table
 * are the operator-visible policy this test guards.
 */
class RingHealthCheckerTest {

    private static final Clock T0 =
            Clock.fixed(Instant.parse("2026-05-25T10:00:00Z"), ZoneOffset.UTC);

    private static StaticPeerListMembership membershipWith(int peerCount) {
        Map<PeerId, PeerAddress> peers = new java.util.LinkedHashMap<>();
        for (int i = 0; i < peerCount; i++) {
            peers.put(PeerId.of("p" + i), PeerAddress.loopback(9000 + i));
        }
        StaticPeerListMembership m = new StaticPeerListMembership(T0, peers);
        m.start();
        return m;
    }

    @Test
    void up_whenAllPeersAlive_andBacklogBelowThreshold() {
        StaticPeerListMembership m = membershipWith(3);
        m.mutableRegistry().transition(PeerId.of("p0"), PeerState.ALIVE);
        m.mutableRegistry().transition(PeerId.of("p1"), PeerState.ALIVE);
        m.mutableRegistry().transition(PeerId.of("p2"), PeerState.ALIVE);
        RingHealthChecker c =
                new RingHealthChecker(T0, m.registry(), () -> 3, () -> 0, 100, true);
        RingHealthStatus  s = c.check();
        assertEquals(RingHealthStatus.Level.UP, s.level());
        assertEquals(3, s.livePeerCount());
        assertEquals(3, s.totalPeerCount());
        assertEquals(0, s.pendingDispatchCount());
        assertTrue(s.isReady());
        assertTrue(s.isFullyHealthy());
    }

    @Test
    void degraded_whenOnePeerSuspect() {
        StaticPeerListMembership m = membershipWith(3);
        m.mutableRegistry().transition(PeerId.of("p0"), PeerState.ALIVE);
        m.mutableRegistry().transition(PeerId.of("p1"), PeerState.ALIVE);
        m.mutableRegistry().transition(PeerId.of("p2"), PeerState.ALIVE);
        m.mutableRegistry().transition(PeerId.of("p1"), PeerState.SUSPECT);
        RingHealthChecker c =
                new RingHealthChecker(T0, m.registry(), () -> 2, () -> 0, 100, true);
        RingHealthStatus  s = c.check();
        assertEquals(RingHealthStatus.Level.DEGRADED, s.level());
        assertTrue(s.isReady(), "DEGRADED must still report ready=true");
        assertFalse(s.isFullyHealthy());
        assertTrue(s.diagnostics().stream().anyMatch(d -> d.contains("SUSPECT")));
    }

    @Test
    void degraded_whenPendingBacklogAboveThreshold() {
        StaticPeerListMembership m = membershipWith(1);
        m.mutableRegistry().transition(PeerId.of("p0"), PeerState.ALIVE);
        RingHealthChecker c =
                new RingHealthChecker(T0, m.registry(), () -> 1, () -> 150, 100, true);
        RingHealthStatus  s = c.check();
        assertEquals(RingHealthStatus.Level.DEGRADED, s.level());
        assertTrue(s.diagnostics().stream().anyMatch(d -> d.contains("backlog 150")));
    }

    @Test
    void down_whenZeroLivePeers_inClusteredDeployment() {
        StaticPeerListMembership m = membershipWith(2);
        // Both peers in JOINING — no ALIVE peers.
        RingHealthChecker c =
                new RingHealthChecker(T0, m.registry(), () -> 0, () -> 0, 100, true);
        RingHealthStatus  s = c.check();
        assertEquals(RingHealthStatus.Level.DOWN, s.level());
        assertFalse(s.isReady(), "DOWN must report ready=false");
    }

    @Test
    void up_whenZeroPeers_inStandaloneDeployment() {
        StaticPeerListMembership m = membershipWith(1);
        // No transitions — peer stays JOINING.
        RingHealthChecker c =
                new RingHealthChecker(T0, m.registry(), () -> 0, () -> 0, 100, false);
        RingHealthStatus  s = c.check();
        assertEquals(
                     RingHealthStatus.Level.UP,
                     s.level(),
                     "standalone deployments do NOT require live peers to be UP");
    }

    @Test
    void checkedAt_isClockInjected() {
        StaticPeerListMembership m = membershipWith(1);
        m.mutableRegistry().transition(PeerId.of("p0"), PeerState.ALIVE);
        RingHealthChecker c =
                new RingHealthChecker(T0, m.registry(), () -> 1, () -> 0, 100, true);
        assertEquals(T0.instant(), c.check().checkedAt());
    }
}

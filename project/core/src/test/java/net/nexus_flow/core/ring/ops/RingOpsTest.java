package net.nexus_flow.core.ring.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ring.membership.DefaultMembershipRegistry;
import net.nexus_flow.core.ring.membership.PeerState;
import net.nexus_flow.core.ring.membership.StaticPeerListMembership;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingAcceptor;
import net.nexus_flow.core.ring.transport.RingAcceptorConfig;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.transport.RingFrameHandler;
import net.nexus_flow.core.ring.wire.RingFrame;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RingOps} as the single read-mostly facade replacing the 5+ object reach-around the
 * audit identified as the canonical operator pain.
 */
class RingOpsTest {

    private static final PeerId REMOTE = PeerId.of("remote");

    @Test
    void facade_aggregates_health_membership_connections_pendingDispatches() throws Exception {
        Map<PeerId, PeerAddress> seeds = new LinkedHashMap<>();
        seeds.put(REMOTE, PeerAddress.loopback(9001));
        StaticPeerListMembership m = new StaticPeerListMembership(Clock.systemUTC(), seeds);
        m.start();
        DefaultMembershipRegistry mutable = m.mutableRegistry();
        mutable.transition(REMOTE, PeerState.ALIVE);

        RingConnectionRegistry connections      = new RingConnectionRegistry();
        AtomicInteger          pendingSimulated = new AtomicInteger();
        try (RingAcceptor acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                new RingFrameHandler() {
                    @Override
                    public void onFrame(RingConnection conn, RingFrame f) {
                        // sink
                    }
                })) {
            RingHealthChecker health = new RingHealthChecker(
                    Clock.systemUTC(), m.registry(), acceptor::liveConnections,
                    pendingSimulated::get, 100, true);

            RingOps ops = RingOps.builder()
                    .health(health)
                    .membership(m.registry())
                    .connections(connections)
                    .acceptor(acceptor)
                    .pendingDispatches(pendingSimulated::get)
                    .build();

            assertEquals(1, ops.peersSnapshot().size(),
                         "facade returns the membership snapshot verbatim");
            assertEquals(0, ops.connectedPeers().size(),
                         "no peers registered in conn registry yet");
            assertEquals(0, ops.pendingDispatchCount());

            RingHealthStatus status = ops.health();
            assertEquals(RingHealthStatus.Level.UP, status.level(),
                         "1 alive peer + 0 pending = UP");

            // simulate a backlog
            pendingSimulated.set(150);
            assertEquals(RingHealthStatus.Level.DEGRADED, ops.health().level(),
                         "pending >= warn threshold ⇒ DEGRADED");
            assertEquals(150, ops.pendingDispatchCount());
        }
    }

    @Test
    void quiesce_returnsTrue_immediately_whenAlreadyQuiet() throws Exception {
        // Always seed at least one peer — StaticPeerListMembership rejects empty seed lists.
        StaticPeerListMembership m = new StaticPeerListMembership(Clock.systemUTC(),
                Map.of(REMOTE, PeerAddress.loopback(9991)));
        m.start();
        try (RingAcceptor acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                new RingFrameHandler() {
                    @Override
                    public void onFrame(RingConnection conn, RingFrame f) {
                        // sink
                    }
                })) {
            RingHealthChecker health = new RingHealthChecker(
                    Clock.systemUTC(), m.registry(), acceptor::liveConnections,
                    () -> 0, 100, false);
            RingOps           ops    = RingOps.builder()
                    .health(health)
                    .membership(m.registry())
                    .connections(new RingConnectionRegistry())
                    .acceptor(acceptor)
                    .pendingDispatches(() -> 0)
                    .build();
            assertTrue(ops.quiesce(Duration.ofSeconds(1)),
                       "0 pending + no outbox bridge ⇒ already quiescent");
        }
    }

    @Test
    void quiesce_returnsFalse_whenPendingNeverDrains() throws Exception {
        StaticPeerListMembership m = new StaticPeerListMembership(Clock.systemUTC(),
                Map.of(REMOTE, PeerAddress.loopback(9992)));
        m.start();
        try (RingAcceptor acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                new RingFrameHandler() {
                    @Override
                    public void onFrame(RingConnection conn, RingFrame f) {
                        // sink
                    }
                })) {
            RingHealthChecker health = new RingHealthChecker(
                    Clock.systemUTC(), m.registry(), acceptor::liveConnections,
                    () -> 5, 100, false);
            RingOps           ops    = RingOps.builder()
                    .health(health)
                    .membership(m.registry())
                    .connections(new RingConnectionRegistry())
                    .acceptor(acceptor)
                    .pendingDispatches(() -> 5) // stuck pending — never drains
                    .build();
            assertFalse(ops.quiesce(Duration.ofMillis(200)),
                        "stuck pending dispatches must cause quiesce to time out and return false");
        }
    }
}

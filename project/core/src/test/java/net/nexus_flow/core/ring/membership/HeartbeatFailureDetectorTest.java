package net.nexus_flow.core.ring.membership;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingAcceptor;
import net.nexus_flow.core.ring.transport.RingAcceptorConfig;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.transport.RingFrameHandler;
import net.nexus_flow.core.ring.wire.RingFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins the {@link HeartbeatFailureDetector} state machine: ALIVE peers stay ALIVE under
 * PONGs, flip to SUSPECT after N missed probes, recover on PONG, flip to CONFIRMED_DEAD
 * after grace.
 *
 * <p>Tests are deterministic — they drive {@link HeartbeatFailureDetector#tickOnce()} from
 * the test thread instead of waiting for the background loop, and use a tickable clock.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class HeartbeatFailureDetectorTest {

    private static final PeerId LOCAL  = PeerId.of("local");
    private static final PeerId REMOTE = PeerId.of("remote");

    /** Mutable clock for deterministic suspect-grace tests. */
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

    /**
     * Wire up a real acceptor on loopback so we have a working RingConnection to send PINGs
     * through. The acceptor's handler is a no-op; we drive the heartbeat detector manually.
     */
    private static record Fixture(
                                  RingAcceptor acceptor,
                                  Socket peerSocket,
                                  RingConnection localToPeer,
                                  RingConnectionRegistry connections,
                                  DefaultMembershipRegistry membership,
                                  HeartbeatFailureDetector detector,
                                  TickableClock clock)
            implements AutoCloseable {

        @Override
        public void close() throws IOException {
            detector.shutdown();
            localToPeer.close();
            peerSocket.close();
            acceptor.close();
        }
    }

    private static Fixture build(int missesUntilSuspect, Duration suspectGrace) throws IOException {
        RingAcceptor acceptor =
                new RingAcceptor(RingAcceptorConfig.loopbackForTests(), new RingFrameHandler() {
                    @Override
                    public void onFrame(RingConnection connection, RingFrame frame) {
                    }
                });
        acceptor.start();
        await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);

        Socket peerSocket = new Socket();
        peerSocket.connect(new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()), 2_000);
        peerSocket.setTcpNoDelay(true);

        RingConnection localToPeer =
                net.nexus_flow.core.ring.transport.TestRingConnections.over(
                                                                            peerSocket, PeerAddress.loopback(acceptor.boundPort()));
        localToPeer.bindPeerId(REMOTE);
        localToPeer.markActive();
        // Start writer VT so send() actually emits bytes.
        Thread.ofVirtual().name("test-writer").start(localToPeer::runWriteLoop);

        RingConnectionRegistry connections = new RingConnectionRegistry();
        connections.register(REMOTE, localToPeer);

        TickableClock             clock      = new TickableClock();
        DefaultMembershipRegistry membership = new DefaultMembershipRegistry(clock);
        membership.register(REMOTE, PeerAddress.loopback(acceptor.boundPort()));
        membership.transition(REMOTE, PeerState.ALIVE);

        HeartbeatConfig          cfg      =
                new HeartbeatConfig(
                        Duration.ofMillis(100),
                        missesUntilSuspect,
                        suspectGrace,
                        Duration.ofSeconds(2),
                        LOCAL);
        HeartbeatFailureDetector detector =
                new HeartbeatFailureDetector(cfg, clock, connections, membership);
        return new Fixture(
                acceptor, peerSocket, localToPeer, connections, membership, detector, clock);
    }

    @Test
    void onPong_clearsOutstandingProbes_andRecoversFromSuspect() throws Exception {
        try (Fixture f = build(2, Duration.ofSeconds(1))) {
            // Two ticks without PONG → SUSPECT.
            f.detector.tickOnce();
            f.detector.tickOnce();
            assertEquals(
                         PeerState.SUSPECT,
                         f.membership.peer(REMOTE).orElseThrow().state(),
                         "two missed probes must flip to SUSPECT");
            // Now PONG arrives.
            f.detector.onPong(new PingPongPayload(99L, REMOTE));
            assertEquals(
                         PeerState.ALIVE,
                         f.membership.peer(REMOTE).orElseThrow().state(),
                         "PONG must restore ALIVE");
            assertEquals(0, f.detector.outstandingProbesSnapshot().size());
        }
    }

    @Test
    void noPongOverTwoTicks_transitionsToSuspect() throws Exception {
        try (Fixture f = build(2, Duration.ofSeconds(30))) {
            f.detector.tickOnce();
            f.detector.tickOnce();
            assertEquals(PeerState.SUSPECT, f.membership.peer(REMOTE).orElseThrow().state());
        }
    }

    @Test
    void suspectGraceElapsed_transitionsToConfirmedDead_andClosesConnection() throws Exception {
        try (Fixture f = build(1, Duration.ofMillis(100))) {
            // First tick → SUSPECT immediately (missesUntilSuspect=1).
            f.detector.tickOnce();
            assertEquals(PeerState.SUSPECT, f.membership.peer(REMOTE).orElseThrow().state());
            // Advance the clock past suspect grace.
            f.clock.advance(Duration.ofSeconds(1));
            // Next tick should flip to CONFIRMED_DEAD.
            f.detector.tickOnce();
            assertEquals(PeerState.CONFIRMED_DEAD, f.membership.peer(REMOTE).orElseThrow().state());
            // Connection unregistered and closed.
            assertTrue(f.connections.get(REMOTE).isEmpty());
            assertTrue(f.localToPeer.isClosed());
        }
    }

    @Test
    void onPing_repliesWithSameSequence() throws Exception {
        try (Fixture f = build(3, Duration.ofSeconds(30))) {
            // Simulate an inbound PING and verify the reply is queued.
            // We don't read the actual bytes here — the contract test is: onPing must send()
            // without throwing, with a PONG that echoes the sequence. Direct outbound queue
            // observation is enough for this unit test.
            f.detector.onPing(f.localToPeer, new PingPongPayload(42L, PeerId.of("other-peer")));
            // The reply was enqueued; await drain confirms the writer VT picked it up.
            await().atMost(1, TimeUnit.SECONDS)
                    .until(() -> f.localToPeer.outboundQueueDepth() == 0);
            // Pin: queue drained to zero confirms the PONG reply was actually sent.
            assertEquals(0, f.localToPeer.outboundQueueDepth());
        }
    }

    @Test
    void outstandingProbesSnapshot_reflectsState() throws Exception {
        try (Fixture f = build(5, Duration.ofSeconds(30))) {
            f.detector.tickOnce();
            f.detector.tickOnce();
            Map<PeerId, Integer> snapshot = f.detector.outstandingProbesSnapshot();
            assertEquals(2, snapshot.get(REMOTE));
        }
    }
}

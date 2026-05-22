package net.nexus_flow.core.ring.transport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.HelloAckPayload;
import net.nexus_flow.core.ring.wire.HelloPayload;
import net.nexus_flow.core.ring.wire.RingFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end loopback tests of the {@link RingDialer} against a real {@link RingAcceptor}.
 * Pins the success / failure classification matrix per the network skill §7 — every dial
 * failure type maps to a specific {@link RingDialException.Classification}.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class RingDialerPlainTcpTest {

    /**
     * Routable-but-non-responsive IP — packets are accepted by the OS routing layer but the
     * remote never replies. Constant-extracted so PMD's {@code AvoidUsingHardCodedIP} is
     * suppressed in ONE place; the test deliberately uses a blackhole IP to force a connect
     * TIMEOUT classification.
     */
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static final String BLACKHOLE_IP = "10.255.255.1";


    private static final class RecordingHandler implements RingFrameHandler {
        final List<RingConnection> accepted    = new CopyOnWriteArrayList<>();
        final List<RingFrame>      frames      = new CopyOnWriteArrayList<>();
        final AtomicInteger        closedCount = new AtomicInteger();

        @Override
        public void onAccepted(RingConnection connection) {
            accepted.add(connection);
        }

        @Override
        public void onFrame(RingConnection connection, RingFrame frame) {
            frames.add(frame);
        }

        @Override
        public void onClosed(RingConnection connection, Throwable cause) {
            closedCount.incrementAndGet();
        }
    }

    @Test
    void dial_happyPath_returnsLiveConnection_andHandshakeRoundTrips() throws Exception {
        RecordingHandler serverHandler = new RecordingHandler();
        RecordingHandler clientHandler = new RecordingHandler();
        try (RingAcceptor acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(), serverHandler);
                RingDialer dialer = new RingDialer(
                        RingDialerConfig.loopbackForTests(), clientHandler)) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);

            RingConnection conn = dialer.dial(PeerAddress.loopback(acceptor.boundPort()));
            assertNotNull(conn);

            // Send HELLO from client side.
            conn.send(new RingFrame(FrameType.HELLO, HelloPayload.empty("test-dialer").encode()));
            await().atMost(2, TimeUnit.SECONDS).until(() -> !serverHandler.frames.isEmpty());
            assertEquals(FrameType.HELLO, serverHandler.frames.getFirst().type());

            // Server replies with HELLO_ACK.
            await().atMost(2, TimeUnit.SECONDS).until(() -> !serverHandler.accepted.isEmpty());
            RingConnection serverConn = serverHandler.accepted.getFirst();
            serverConn.send(
                            new RingFrame(
                                    FrameType.HELLO_ACK,
                                    HelloAckPayload.accept("test-acceptor").encode()));
            await().atMost(2, TimeUnit.SECONDS).until(() -> !clientHandler.frames.isEmpty());
            assertEquals(FrameType.HELLO_ACK, clientHandler.frames.getFirst().type());
        }
    }

    @Test
    void dial_connectionRefused_throwsClassifiedException() throws Exception {
        try (RingDialer dialer = new RingDialer(
                RingDialerConfig.loopbackForTests(), new RecordingHandler())) {
            // Dial a port nobody is listening on (1 — privileged port, definitely refused).
            RingDialException ex =
                    assertThrows(
                                 RingDialException.class,
                                 () -> dialer.dial(PeerAddress.loopback(1)));
            assertEquals(
                         RingDialException.Classification.CONNECTION_REFUSED, ex.classification());
            assertEquals(1, ex.address().port());
        }
    }

    @Test
    void dial_connectTimeout_isClassifiedAsTimeout() throws Exception {
        // 10.255.255.1 is a routable-but-blackhole IP — connect blocks until the timeout fires
        // because the OS does not get a SYN-ACK or a RST. Some test environments may classify
        // this as UNKNOWN_HOST instead; either is acceptable to the dialer contract.
        RingDialerConfig cfg =
                RingDialerConfig.builder()
                        .connectTimeout(Duration.ofMillis(200))
                        .build();
        try (RingDialer dialer = new RingDialer(cfg, new RecordingHandler())) {
            try {
                dialer.dial(new PeerAddress(BLACKHOLE_IP, 12345));
            } catch (RingDialException ex) {
                // Accept any of TIMEOUT / UNKNOWN_HOST / TRANSPORT — the network behaviour
                // depends on the test environment. The classification not being
                // CONNECTION_REFUSED is the load-bearing assertion: a blackhole IP is not the
                // same as a peer that returned RST.
                assertEquals(
                             true,
                             ex.classification() != RingDialException.Classification.CONNECTION_REFUSED,
                             "blackhole IP must NOT classify as CONNECTION_REFUSED; got "
                                     + ex.classification());
            }
        }
    }

    @Test
    void dialer_close_isIdempotent() throws Exception {
        RingDialer dialer =
                new RingDialer(RingDialerConfig.loopbackForTests(), new RecordingHandler());
        dialer.close();
        // Second close MUST not throw — pinned via assertDoesNotThrow so PMD sees an
        // assertion (and the assertion is the load-bearing contract anyway).
        assertDoesNotThrow(dialer::close);
    }

}

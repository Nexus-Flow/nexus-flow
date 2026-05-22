package net.nexus_flow.core.ring.transport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.ring.wire.FrameDecoder;
import net.nexus_flow.core.ring.wire.FrameEncoder;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.HelloAckPayload;
import net.nexus_flow.core.ring.wire.HelloPayload;
import net.nexus_flow.core.ring.wire.RingFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Plain-TCP loopback integration tests for the {@link RingAcceptor} accept path. mTLS is
 * exercised in a separate test that pre-generates a test keystore — these tests focus on the
 * accept loop, frame decode round-trip, capacity enforcement, and clean shutdown semantics.
 *
 * <p>Per the {@code nexus-java-network-io-lowlevel} skill §21: bind to loopback with port
 * {@code 0}, read the OS-assigned port, never use sleeps for synchronisation (Awaitility +
 * latches instead).
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class RingAcceptorPlainTcpTest {

    /** Helper handler that records every callback for assertion. */
    private static final class RecordingHandler implements RingFrameHandler {
        final List<RingConnection>       accepted       = new CopyOnWriteArrayList<>();
        final List<RingFrame>            frames         = new CopyOnWriteArrayList<>();
        final AtomicInteger              closedCount    = new AtomicInteger();
        final AtomicReference<Throwable> lastCloseCause = new AtomicReference<>();

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
            lastCloseCause.set(cause);
            closedCount.incrementAndGet();
        }
    }

    private static RingAcceptor startEphemeralAcceptor(RingFrameHandler handler) {
        RingAcceptor acceptor =
                new RingAcceptor(RingAcceptorConfig.loopbackForTests(), handler);
        acceptor.start();
        // Wait until the daemon thread binds the socket.
        await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
        return acceptor;
    }

    @Test
    void acceptor_bindsEphemeralPort_andReportsItAfterStart() {
        try (RingAcceptor acceptor = startEphemeralAcceptor(new RecordingHandler())) {
            assertTrue(acceptor.boundPort() > 0, "ephemeral port assigned");
            assertNotEquals(0, acceptor.boundPort(), "must NOT report the requested 0 sentinel");
        }
    }

    @Test
    void singleConnection_handshakeRoundTrip_handlerSeesHelloAndAck() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (RingAcceptor acceptor = startEphemeralAcceptor(handler)) {
            // Dial as a raw client.
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()), 2_000);
                client.setTcpNoDelay(true);

                // Send HELLO.
                HelloPayload hello = HelloPayload.empty("test-dialer");
                writeFrame(client, new RingFrame(FrameType.HELLO, hello.encode()));

                // Wait for handler to observe it.
                await().atMost(2, TimeUnit.SECONDS).until(() -> !handler.frames.isEmpty());
                RingFrame received = handler.frames.getFirst();
                assertEquals(FrameType.HELLO, received.type());
                HelloPayload decoded = HelloPayload.decode(received.bodyBytes());
                assertEquals("test-dialer", decoded.peerId());

                // Server-side: handler sends a HELLO_ACK back through the connection.
                RingConnection serverSide = handler.accepted.getFirst();
                serverSide.send(
                                new RingFrame(
                                        FrameType.HELLO_ACK,
                                        HelloAckPayload.accept("test-acceptor").encode()));

                // Read the ack on the client socket.
                RingFrame ack = readOneFrame(client);
                assertEquals(FrameType.HELLO_ACK, ack.type());
                HelloAckPayload decodedAck = HelloAckPayload.decode(ack.bodyBytes());
                assertEquals(HelloAckPayload.Decision.ACCEPT, decodedAck.decision());
                assertEquals("test-acceptor", decodedAck.responderPeerId());
            }
        }
    }

    @Test
    void cleanClientDisconnect_handlerOnClosedFires_withNullCause() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (RingAcceptor acceptor = startEphemeralAcceptor(handler)) {
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()), 2_000);
                // Send one HELLO so the handler registers, then close.
                writeFrame(client, new RingFrame(FrameType.HELLO, HelloPayload.empty("p").encode()));
                await().atMost(2, TimeUnit.SECONDS).until(() -> !handler.frames.isEmpty());
            }
            await().atMost(2, TimeUnit.SECONDS).until(() -> handler.closedCount.get() == 1);
            // Clean EOF — cause is null.
            assertEquals(null, handler.lastCloseCause.get());
        }
    }

    @Test
    void capacityExceeded_acceptorRefusesAdditionalConnections() throws Exception {
        // Build acceptor with max=1.
        RecordingHandler   handler = new RecordingHandler();
        RingAcceptorConfig cfg     =
                RingAcceptorConfig.builder()
                        .bindAddress(PeerAddress.LOOPBACK_HOST)
                        .port(0)
                        .maxConnections(1)
                        .timeouts(new net.nexus_flow.core.ring.transport.ConnectionTimeouts(
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(1)))
                        .shutdownGrace(Duration.ofSeconds(2))
                        .outboundQueueCapacity(8)
                        .requireTlsInProduction(false)
                        .build();
        try (RingAcceptor acceptor = new RingAcceptor(cfg, handler)) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
            try (Socket first = new Socket()) {
                first.connect(new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()), 2_000);
                await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.liveConnections() == 1);

                // Second connection: socket connects but is closed immediately by the acceptor.
                try (Socket second = new Socket()) {
                    second.connect(new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()), 2_000);
                    // Read on the second client should see EOF promptly.
                    second.setSoTimeout(2_000);
                    int b = second.getInputStream().read();
                    assertEquals(-1, b, "acceptor must close the over-capacity connection promptly");
                }
            }
        }
    }

    @Test
    void shutdown_isIdempotent_andLiveConnectionsGoToZero() throws Exception {
        RecordingHandler handler  = new RecordingHandler();
        RingAcceptor     acceptor = startEphemeralAcceptor(handler);
        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()), 2_000);
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.liveConnections() == 1);
        }
        // Now the client closed — handler observes onClosed.
        await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.liveConnections() == 0);
        acceptor.shutdown();
        // Idempotent: second call is a no-op.
        acceptor.shutdown();
        assertEquals(-1, acceptor.boundPort(), "boundPort cleared after shutdown");
    }

    @Test
    void sendOnClosedConnection_throwsIllegalStateException() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (RingAcceptor acceptor = startEphemeralAcceptor(handler)) {
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()), 2_000);
                await().atMost(2, TimeUnit.SECONDS).until(() -> !handler.accepted.isEmpty());
            }
            await().atMost(2, TimeUnit.SECONDS).until(() -> handler.closedCount.get() == 1);
            RingConnection conn = handler.accepted.getFirst();
            assertTrue(conn.isClosed());
            assertThrows(
                         IllegalStateException.class,
                         () -> conn.send(RingFrame.ofType(FrameType.PING)));
        }
    }

    @Test
    void bindPeerId_isOneShot_secondCallThrows() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (RingAcceptor acceptor = startEphemeralAcceptor(handler)) {
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()), 2_000);
                writeFrame(client, new RingFrame(FrameType.HELLO, HelloPayload.empty("p").encode()));
                await().atMost(2, TimeUnit.SECONDS).until(() -> !handler.accepted.isEmpty());
                RingConnection conn = handler.accepted.getFirst();
                conn.bindPeerId(PeerId.of("test-peer"));
                assertEquals("test-peer", conn.peerId().value());
                assertThrows(
                             IllegalStateException.class, () -> conn.bindPeerId(PeerId.of("other")));
            }
        }
    }

    // ----- raw frame I/O helpers for the test client -----

    private static void writeFrame(Socket socket, RingFrame frame) throws IOException {
        byte[]       bytes = FrameEncoder.encode(frame);
        OutputStream out   = socket.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    private static RingFrame readOneFrame(Socket socket) throws IOException {
        socket.setSoTimeout(5_000);
        InputStream                in  = socket.getInputStream();
        FrameDecoder               dec = new FrameDecoder();
        AtomicReference<RingFrame> got = new AtomicReference<>();
        byte[]                     buf = new byte[1024];
        while (got.get() == null) {
            int n = in.read(buf);
            if (n < 0) {
                throw new IOException("EOF before frame complete");
            }
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
            dec.tryDecode(bb, got::set);
        }
        assertNotNull(got.get());
        return got.get();
    }
}

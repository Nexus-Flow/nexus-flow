package net.nexus_flow.core.ring.transport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.HelloPayload;
import net.nexus_flow.core.ring.wire.RingFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Lifecycle invariants pinned end-to-end via the final {@link RingConnection.Builder} API:
 * phase machine transitions, handshake timeout, idle timeout, truthful send/close, and the
 * {@link RingConnection.SendCompletion} contract.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class RingConnectionLifecycleTest {

    private static class CapturingHandler implements RingFrameHandler {
        final List<RingFrame>                 frames     = new CopyOnWriteArrayList<>();
        final AtomicInteger                   closed     = new AtomicInteger();
        final AtomicReference<Throwable>      closeCause = new AtomicReference<>();
        final AtomicReference<RingConnection> connection = new AtomicReference<>();

        @Override
        public void onAccepted(RingConnection c) {
            connection.set(c);
        }

        @Override
        public void onFrame(RingConnection c, RingFrame f) {
            frames.add(f);
        }

        @Override
        public void onClosed(RingConnection c, Throwable cause) {
            closeCause.set(cause);
            closed.incrementAndGet();
        }
    }

    @Test
    void handshakeTimeout_fires_whenClientNeverSendsHello() throws Exception {
        // 1s handshake timeout, 30s idle — handshake budget should win.
        ConnectionTimeouts tightHandshake = new ConnectionTimeouts(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1));
        RingAcceptorConfig cfg            = RingAcceptorConfig.builder()
                .bindAddress(PeerAddress.LOOPBACK_HOST)
                .port(0)
                .maxConnections(4)
                .timeouts(tightHandshake)
                .shutdownGrace(Duration.ofSeconds(2))
                .outboundQueueCapacity(8)
                .requireTlsInProduction(false)
                .build();
        CapturingHandler   handler        = new CapturingHandler();
        try (RingAcceptor acceptor = new RingAcceptor(cfg, handler)) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
            try (Socket silent = new Socket()) {
                silent.connect(
                               new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()),
                               2_000);
                // Sit silent — server's handshake SO_TIMEOUT must fire within ~1s.
                await().atMost(3, TimeUnit.SECONDS).until(() -> handler.closed.get() == 1);
                assertNotNull(handler.closeCause.get(),
                              "handshake timeout must surface as the close cause, not as a clean EOF");
                assertTrue(
                           handler.closeCause.get() instanceof java.net.SocketTimeoutException,
                           "expected SocketTimeoutException, got " + handler.closeCause.get());
            }
        }
    }

    @Test
    void idleTimeout_fires_afterHandshakeWhenPeerStopsSending() throws Exception {
        // Tight idle timeout (500ms) — handshake is comfortable at 2s.
        ConnectionTimeouts tightIdle = new ConnectionTimeouts(
                Duration.ofSeconds(2),
                Duration.ofMillis(500),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1));
        RingAcceptorConfig cfg       = RingAcceptorConfig.builder()
                .bindAddress(PeerAddress.LOOPBACK_HOST)
                .port(0)
                .maxConnections(4)
                .timeouts(tightIdle)
                .shutdownGrace(Duration.ofSeconds(2))
                .requireTlsInProduction(false)
                .build();
        CapturingHandler   handler   = new CapturingHandler() {
                                         @Override
                                         public void onFrame(RingConnection c, RingFrame f) {
                                             super.onFrame(c, f);
                                             // Bind peer id so the connection promotes out of handshake.
                                             if (f.type() == FrameType.HELLO && c.peerId() == null) {
                                                 c.bindPeerId(PeerId.of("dialer"));
                                                 c.markActive();
                                             }
                                         }
                                     };
        try (RingAcceptor acceptor = new RingAcceptor(cfg, handler)) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
            try (Socket client = new Socket()) {
                client.connect(
                               new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()),
                               2_000);
                // Send HELLO so the connection moves to ACTIVE.
                writeFrame(client.getOutputStream(),
                           new RingFrame(FrameType.HELLO, HelloPayload.empty("dialer").encode()));
                await().atMost(2, TimeUnit.SECONDS).until(() -> !handler.frames.isEmpty());
                // Now go idle — server should close in ~500ms.
                await().atMost(2, TimeUnit.SECONDS).until(() -> handler.closed.get() == 1);
                assertTrue(
                           handler.closeCause.get() instanceof java.net.SocketTimeoutException,
                           "idle close must surface as SocketTimeoutException");
            }
        }
    }

    @Test
    void sendCompletion_firesSuccessTrue_whenFrameIsActuallyWritten() throws Exception {
        try (RingAcceptor acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                (c, frame) -> {
                    /* sink */ })) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
            try (Socket socket = new Socket()) {
                socket.connect(
                               new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()),
                               2_000);
                try (RingConnection conn = TestRingConnections.over(
                                                                    socket, PeerAddress.loopback(acceptor.boundPort()))) {
                    // Start writer + bind to ACTIVE (skip handshake — we exercise the wire path).
                    Thread writer = Thread.ofVirtual().start(conn::runWriteLoop);
                    conn.bindPeerId(PeerId.of("p"));
                    conn.markActive();

                    CompletableFuture<Boolean> ok      = new CompletableFuture<>();
                    var                        outcome = conn.send(
                                                                   new RingFrame(FrameType.PING, new byte[]{1, 2, 3}),
                                                                   (success, _cause) -> ok.complete(success));
                    assertEquals(RingConnection.SendOutcome.ENQUEUED, outcome);
                    assertTrue(ok.get(2, TimeUnit.SECONDS),
                               "completion must fire success=true after the writer flushes");
                    conn.close();
                    writer.join(2_000);
                }
            }
        }
    }

    @Test
    void close_drainsQueuedFrames_failingTheirCompletionsExactlyOnce() throws Exception {
        // Use a real connected socket pair but block the writer by NOT starting it. Every
        // send accumulates in the outbound queue. close() must fail every pending completion.
        try (RingAcceptor acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                (c, frame) -> {
                    /* sink */ })) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
            try (Socket socket = new Socket()) {
                socket.connect(
                               new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()),
                               2_000);
                RingConnection conn = TestRingConnections.over(
                                                               socket, PeerAddress.loopback(acceptor.boundPort()));
                conn.bindPeerId(PeerId.of("p"));
                conn.markActive();
                List<CompletableFuture<Boolean>> outcomes = new CopyOnWriteArrayList<>();
                for (int i = 0; i < 5; i++) {
                    CompletableFuture<Boolean> f = new CompletableFuture<>();
                    outcomes.add(f);
                    conn.send(new RingFrame(FrameType.PING, new byte[]{(byte) i}),
                              (success, _cause) -> f.complete(success));
                }
                // Start writer THEN immediately close — race between drain and close.
                Thread writer = Thread.ofVirtual().start(conn::runWriteLoop);
                conn.close(new IOException("test-close"));
                writer.join(2_000);
                // Every completion fired exactly once; pending-queue frames went out OR were
                // rejected, but every callback completed.
                for (CompletableFuture<Boolean> f : outcomes) {
                    assertTrue(f.isDone(), "every send completion MUST fire by the time close returns");
                }
            }
        }
    }

    @Test
    void send_afterClose_returnsRejectedClosed_andFiresCompletionFalse() throws Exception {
        try (RingAcceptor acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                (c, frame) -> {
                    /* sink */ })) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
            try (Socket socket = new Socket()) {
                socket.connect(
                               new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()),
                               2_000);
                RingConnection conn = TestRingConnections.over(
                                                               socket, PeerAddress.loopback(acceptor.boundPort()));
                conn.bindPeerId(PeerId.of("p"));
                conn.markActive();
                conn.close();

                CompletableFuture<Throwable> cause   = new CompletableFuture<>();
                var                          outcome = conn.send(
                                                                 new RingFrame(FrameType.PING, new byte[]{1}),
                                                                 (success, c) -> cause.complete(c));
                assertEquals(RingConnection.SendOutcome.REJECTED_CLOSED, outcome);
                Throwable c = cause.get(1, TimeUnit.SECONDS);
                assertNotNull(c, "completion MUST fire with a non-null cause on REJECTED_CLOSED");
            }
        }
    }

    @Test
    void send_duringHandshake_returnsRejectedHandshake_forSocketNotActiveYet() throws Exception {
        // Build a stub connection that is still in ACCEPTED phase (no read loop kicked off
        // to advance to PROTOCOL_HANDSHAKING). Sending from outside must be rejected.
        RingConnection               stub    = TestRingConnections.stub();
        CompletableFuture<Throwable> cause   = new CompletableFuture<>();
        var                          outcome = stub.send(
                                                         new RingFrame(FrameType.PING, new byte[]{1}),
                                                         (success, c) -> cause.complete(c));
        assertEquals(RingConnection.SendOutcome.REJECTED_HANDSHAKE, outcome);
        assertNotNull(cause.get(1, TimeUnit.SECONDS));
        stub.close();
    }

    @Test
    void close_isIdempotent_acrossManyConcurrentCallers() throws Exception {
        try (RingAcceptor acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                (c, frame) -> {
                    /* sink */ })) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
            try (Socket socket = new Socket()) {
                socket.connect(
                               new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()),
                               2_000);
                RingConnection conn = TestRingConnections.over(
                                                               socket, PeerAddress.loopback(acceptor.boundPort()));
                conn.bindPeerId(PeerId.of("p"));
                conn.markActive();
                // 32 concurrent close calls — must converge to CLOSED with no exception.
                AtomicInteger thrown = new AtomicInteger();
                List<Thread>  ts     = new java.util.ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    Thread t = Thread.ofVirtual().start(() -> {
                        try {
                            conn.close();
                        } catch (Throwable _t) {
                            thrown.incrementAndGet();
                        }
                    });
                    ts.add(t);
                }
                for (Thread t : ts) {
                    t.join(5_000);
                }
                await().atMost(5, TimeUnit.SECONDS)
                        .until(() -> conn.phase() == ConnectionPhase.CLOSED);
                assertEquals(0, thrown.get(),
                             "close() MUST be idempotent across concurrent callers");
            }
        }
    }

    @Test
    void phase_machine_transitionsAreMonotonic() throws Exception {
        RingConnection stub = TestRingConnections.stub();
        assertEquals(ConnectionPhase.ACCEPTED, stub.phase());
        // backward transition refused
        assertFalse(stub.advancePhase(ConnectionPhase.ACCEPTED));
        // forward OK
        assertTrue(stub.advancePhase(ConnectionPhase.PROTOCOL_HANDSHAKING));
        assertEquals(ConnectionPhase.PROTOCOL_HANDSHAKING, stub.phase());
        // jumping ahead OK
        assertTrue(stub.advancePhase(ConnectionPhase.ACTIVE));
        // cannot go back
        assertFalse(stub.advancePhase(ConnectionPhase.PROTOCOL_HANDSHAKING));
        stub.close();
        assertEquals(ConnectionPhase.CLOSED, stub.phase());
        assertNull(stub.peerId());
    }

    @Test
    void backpressureRejection_byByteCapacity_evenWhenFrameCountStillFits() throws Exception {
        // Build a connection with a tiny byte budget but a generous frame budget.
        try (RingAcceptor acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                (c, frame) -> {
                    /* sink */ })) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
            try (Socket socket = new Socket()) {
                socket.connect(
                               new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()),
                               2_000);
                RingConnection conn = RingConnection.builder()
                        .socket(socket)
                        .decoder(new net.nexus_flow.core.ring.wire.FrameDecoder())
                        .outboundQueueCapacity(1024)
                        .outboundQueueByteCapacity(64) // bytes — exactly one tiny frame fits
                        .timeouts(TestRingConnections.loopbackTimeouts())
                        .remoteAddress(PeerAddress.loopback(acceptor.boundPort()))
                        .build();
                conn.bindPeerId(PeerId.of("p"));
                conn.markActive();
                // Do NOT start writer — accumulate frames until byte cap rejects.
                int accepted = 0;
                int rejected = 0;
                for (int i = 0; i < 10; i++) {
                    var outcome = conn.send(
                                            new RingFrame(FrameType.PING, new byte[]{1, 2, 3, 4}),
                                            RingConnection.SendCompletion.NOOP);
                    if (outcome == RingConnection.SendOutcome.ENQUEUED) {
                        accepted++;
                    } else if (outcome == RingConnection.SendOutcome.REJECTED_BACKPRESSURE) {
                        rejected++;
                    }
                }
                assertTrue(accepted >= 1, "at least one frame should fit");
                assertTrue(rejected >= 1,
                           "byte-cap backpressure rejection MUST fire before frame count cap");
                conn.close();
            }
        }
    }

    private static void writeFrame(OutputStream out, RingFrame frame) throws IOException {
        byte[] bytes = net.nexus_flow.core.ring.wire.FrameEncoder.encode(frame);
        out.write(bytes);
        out.flush();
    }
}

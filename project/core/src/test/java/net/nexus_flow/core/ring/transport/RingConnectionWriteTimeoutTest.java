package net.nexus_flow.core.ring.transport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.RingFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class RingConnectionWriteTimeoutTest {

    /**
     * Pins that the write-timeout watchdog fires when frames have been queued for longer
     * than {@link ConnectionTimeouts#write()} with no writer progress. Connection closes
     * with a write-timeout cause; pending send completions fire with failure.
     */
    @Test
    void writeWatchdog_closesConnection_whenQueuedFrameMakesNoProgress() throws Exception {
        // Build an acceptor with a very tight write timeout. The remote side is a stub
        // socket whose OutputStream blocks forever — perfect simulation of a slow peer.
        try (RingAcceptor acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                (c, frame) -> {
                    /* sink */ })) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);

            // Client-side: real socket, but we replace the OutputStream with one that
            // blocks indefinitely on write so the writer VT cannot make progress.
            Socket realSocket = new Socket();
            realSocket.connect(
                               new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()),
                               2_000);
            Socket blockedSocket = new BlockedWriteSocket(realSocket);

            ConnectionTimeouts tightWrite = new ConnectionTimeouts(
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
                    Duration.ofMillis(500),
                    Duration.ofSeconds(1));
            RingConnection     conn       = RingConnection.builder()
                    .socket(blockedSocket)
                    .decoder(new net.nexus_flow.core.ring.wire.FrameDecoder())
                    .outboundQueueCapacity(8)
                    .outboundQueueByteCapacity(1024 * 1024)
                    .timeouts(tightWrite)
                    .remoteAddress(PeerAddress.loopback(acceptor.boundPort()))
                    .build();
            Thread             writer     = Thread.ofVirtual().start(conn::runWriteLoop);
            conn.bindPeerId(PeerId.of("p"));
            conn.markActive();

            CompletableFuture<Boolean> outcome = new CompletableFuture<>();
            conn.send(new RingFrame(FrameType.PING, new byte[]{1}),
                      (success, _cause) -> outcome.complete(success));

            // Watchdog ticks are driven by the acceptor's scheduled task — but the client
            // connection isn't owned by that acceptor. So we tick the watchdog manually
            // (as production callers would tie to their own scheduler).
            await().atMost(5, TimeUnit.SECONDS).until(() -> {
                boolean closed = conn.checkWriteTimeout();
                return closed || conn.phase() == ConnectionPhase.CLOSED;
            });
            assertEquals(ConnectionPhase.CLOSED, conn.phase(),
                         "write watchdog must close the connection on timeout");
            assertTrue(outcome.get(2, TimeUnit.SECONDS) == Boolean.FALSE,
                       "queued send completion must fire with failure when the watchdog closes"
                               + " the connection");
            writer.join(2_000);
        }
    }

    /**
     * Socket whose OutputStream blocks forever — used to simulate a slow / unresponsive
     * peer.
     */
    private static final class BlockedWriteSocket extends Socket {
        private final Socket       inner;
        private final OutputStream blockingOut = new OutputStream() {
                                                   @Override
                                                   public void write(int b) throws IOException {
                                                       blockForever();
                                                   }

                                                   @Override
                                                   public void write(byte[] b, int off, int len) throws IOException {
                                                       blockForever();
                                                   }

                                                   private void blockForever() throws IOException {
                                                       try {
                                                           Thread.sleep(60_000);
                                                       } catch (InterruptedException ie) {
                                                           Thread.currentThread().interrupt();
                                                           throw new IOException("interrupted", ie);
                                                       }
                                                   }
                                               };

        BlockedWriteSocket(Socket inner) {
            this.inner = inner;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return inner.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() {
            return blockingOut;
        }

        @Override
        public void setTcpNoDelay(boolean on) throws java.net.SocketException {
            inner.setTcpNoDelay(on);
        }

        @Override
        public void setKeepAlive(boolean on) throws java.net.SocketException {
            inner.setKeepAlive(on);
        }

        @Override
        public void setSoTimeout(int timeout) throws java.net.SocketException {
            inner.setSoTimeout(timeout);
        }

        @Override
        public java.net.InetAddress getInetAddress() {
            return inner.getInetAddress();
        }

        @Override
        public int getPort() {
            return inner.getPort();
        }

        @Override
        public java.net.SocketAddress getRemoteSocketAddress() {
            return inner.getRemoteSocketAddress();
        }

        @Override
        public void close() throws IOException {
            inner.close();
        }

        @Override
        public boolean isClosed() {
            return inner.isClosed();
        }
    }
}

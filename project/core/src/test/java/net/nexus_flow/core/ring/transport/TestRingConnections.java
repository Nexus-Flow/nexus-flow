package net.nexus_flow.core.ring.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.wire.FrameDecoder;

/**
 * Cross-package test helper that builds {@link RingConnection} instances via the FINAL public
 * API (the {@link RingConnection.Builder}) using the same loopback-friendly defaults that the
 * old {@code legacyForTests} factory used to provide.
 *
 * <p>Lives in {@code src/test} so it never ships in the production jar — tests across all ring
 * sub-packages reuse it without any compatibility-shim API on the production type.
 */
public final class TestRingConnections {

    private TestRingConnections() {
    }

    /** Loopback-friendly timeouts: 2 s handshake, 30 s idle, 2 s write, 1 s drain. */
    public static ConnectionTimeouts loopbackTimeouts() {
        return new ConnectionTimeouts(
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1));
    }

    /**
     * Build a {@link RingConnection} for tests over an already-connected {@link Socket} using
     * the public Builder + a no-op metrics sink.
     */
    public static RingConnection over(Socket socket, PeerAddress remote) throws IOException {
        return RingConnection.builder()
                .socket(socket)
                .decoder(new FrameDecoder())
                .outboundQueueCapacity(64)
                .outboundQueueByteCapacity(8L * 1024L * 1024L)
                .timeouts(loopbackTimeouts())
                .remoteAddress(remote)
                .metrics(RingMetrics.noOp())
                .build();
    }

    /**
     * Build a {@link RingConnection} over a virtual stub socket (no real I/O). Used by tests
     * that exercise the in-memory routing of frames where the wire path is irrelevant.
     */
    public static RingConnection stub(PeerAddress remote) throws IOException {
        Socket socket = new Socket() {
            @Override
            public InputStream getInputStream() {
                return InputStream.nullInputStream();
            }

            @Override
            public OutputStream getOutputStream() {
                return OutputStream.nullOutputStream();
            }
        };
        return over(socket, remote);
    }

    /** Stub at a default loopback address. */
    public static RingConnection stub() throws IOException {
        return stub(PeerAddress.loopback(9000));
    }
}

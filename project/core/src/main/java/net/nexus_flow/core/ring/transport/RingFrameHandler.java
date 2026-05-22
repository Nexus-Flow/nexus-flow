package net.nexus_flow.core.ring.transport;

import net.nexus_flow.core.ring.wire.RingFrame;
import org.jspecify.annotations.Nullable;

/**
 * SPI plugged into the transport so higher layers (membership, routing, event fan-out, saga
 * lease) receive callbacks without R2/R3 knowing about them.
 *
 * <p>Implementations are <strong>not</strong> invoked on a selector thread — every callback
 * runs on the connection's read virtual thread, so blocking inside a callback only blocks that
 * one connection's read loop. Callbacks SHOULD still be fast and non-blocking against
 * unrelated peers; long work should be handed off to a separate executor.
 *
 * <h2>Lifecycle ordering</h2>
 *
 * <ol>
 * <li>{@link #onAccepted(RingConnection)} fires after the transport-level connect/accept
 * succeeds (and after TLS handshake if applicable). The peer's identity is NOT yet known
 * — the {@code RingConnection.peerId()} returns {@code null}.
 * <li>The connection's read loop then expects a {@link
 * net.nexus_flow.core.ring.wire.FrameType#HELLO} as the first frame. The handler may
 * implement the handshake logic itself (e.g. R4 membership) by consuming the HELLO,
 * validating fingerprints, and calling {@link RingConnection#bindPeerId(PeerId)} to
 * commit the identity.
 * <li>{@link #onFrame(RingConnection, RingFrame)} fires once per decoded frame thereafter.
 * <li>{@link #onClosed(RingConnection, Throwable)} fires exactly once when the connection
 * ends (EOF, idle timeout, protocol exception, shutdown). After this call the
 * connection's resources are released; the handler MUST NOT call {@link
 * RingConnection#send(RingFrame)} on a closed connection (it will throw).
 * </ol>
 */
@FunctionalInterface
public interface RingFrameHandler {

    /**
     * Invoked once per decoded frame. The frame is owned by the caller (the connection's
     * read loop will not reuse it), so the handler may capture it for asynchronous processing
     * if needed.
     *
     * @param connection the connection the frame arrived on
     * @param frame      the decoded frame
     */
    void onFrame(RingConnection connection, RingFrame frame);

    /**
     * Optional: invoked once when the connection enters the established state. Default no-op.
     *
     * @param connection the freshly-established connection (peerId not yet bound)
     */
    default void onAccepted(RingConnection connection) {
    }

    /**
     * Optional: invoked exactly once when the connection terminates. Default no-op.
     *
     * @param connection the closing connection
     * @param cause      the cause of closure: {@code null} for a clean EOF / shutdown,
     *                   {@link java.io.IOException} for transport failure, {@link
     *                   net.nexus_flow.core.ring.wire.RingProtocolException} for protocol violation,
     *                   {@link java.net.SocketTimeoutException} for idle timeout
     */
    default void onClosed(RingConnection connection, @Nullable Throwable cause) {
    }
}

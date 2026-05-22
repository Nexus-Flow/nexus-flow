package net.nexus_flow.core.ring.wire;

import java.io.Serial;

/**
 * Thrown when a frame on the ring wire violates a protocol invariant — bad magic, unknown
 * version, unknown frame type, malformed length, body contents that do not parse, etc.
 *
 * <p>A {@code RingProtocolException} on a connection is a HARD signal: the receiver MUST close
 * the connection (cannot recover the byte stream after a malformed frame) and the sender peer
 * SHOULD be classified as suspect by gossip — repeated protocol exceptions from the same peer
 * are a strong indicator of a corrupted binary, a compromised TLS endpoint, or a misconfigured
 * version skew.
 *
 * <p>The class is unchecked: the framework's I/O paths translate transport-level failures into
 * {@link net.nexus_flow.core.runtime.result.DispatchResult.Failure} at the dispatch boundary,
 * not via checked exceptions on every read call.
 */
public class RingProtocolException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message human-readable diagnostic; included in logs / metrics tags via the caller's
     *                error policy
     */
    public RingProtocolException(String message) {
        super(message);
    }

    /**
     * @param message human-readable diagnostic
     * @param cause   the underlying cause — typically an {@link java.io.IOException} from the
     *                transport layer or a {@link RuntimeException} from a body codec
     */
    public RingProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}

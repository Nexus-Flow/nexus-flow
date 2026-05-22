package net.nexus_flow.core.outbox;

import java.io.Serial;

/**
 * raised by {@link OutboxPayloadCodec} implementations when encoding or decoding fails. Always
 * carries the offending event type (or the requested payload type, on decode) in the message and
 * the underlying cause through {@link #getCause()}.
 */
public final class OutboxCodecException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a descriptive message and the underlying cause.
     *
     * @param message human-readable description including the offending event type
     * @param cause   the underlying encode/decode failure
     */
    public OutboxCodecException(String message, Throwable cause) {
        // Stack-traceless — the cause (typically SerializationException / IOException) carries
        // the real codec-internal stack trace. The wrapper's trace would only point to
        // OutboxAppender.encode/Decoder.decode boundary. Suppression chain stays active.
        // Saves ~200 ns per codec failure throw.
        super(message, cause, /* enableSuppression= */ true, /* writableStackTrace= */ false);
    }

    /**
     * Constructs the exception with a descriptive message and no cause.
     *
     * <p>Prefer {@link #OutboxCodecException(String, Throwable)} when an underlying exception is
     * available.
     *
     * @param message human-readable description of the codec failure
     */
    public OutboxCodecException(String message) {
        // Same stack-traceless rationale as the (message, cause) constructor — the actionable
        // diagnostic is the message itself (the codec's view of what went wrong); the throw
        // site is always one of two well-known codec entry points. Suppression chain stays
        // active.
        super(message, null, /* enableSuppression= */ true, /* writableStackTrace= */ false);
    }
}

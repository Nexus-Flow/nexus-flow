package net.nexus_flow.core.ring.wire;

import java.util.Objects;

/**
 * A decoded ring frame — frame type + body. Wire encoding/decoding is handled by
 * {@link FrameEncoder} and {@link FrameDecoder} respectively.
 *
 * <h2>Body ownership</h2>
 *
 * The frame holds a {@link FrameBody}, not a raw {@code byte[]}. This is the zero-copy
 * shape: handing a frame down the dispatch chain does NOT clone the body, and writing it to
 * a socket does NOT clone the body. Constructors and factories that accept {@code byte[]}
 * defensively copy into a fresh {@code FrameBody}; the {@link #wrapping(FrameType, byte[])}
 * factory takes ownership of the array (caller MUST NOT retain it) for the hot path.
 *
 * @param type the frame type
 * @param body the body view; never {@code null}; {@link FrameBody#empty()} for type-only frames
 */
public record RingFrame(FrameType type, FrameBody body) {

    /** Compact constructor with non-null validation. */
    public RingFrame {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(body, "body");
    }

    /**
     * Convenience constructor accepting a raw byte array (defensively copied). Use
     * {@link #wrapping(FrameType, byte[])} on the hot path when the caller can transfer
     * ownership instead of forcing a copy.
     *
     * @param type the frame type
     * @param body the body bytes; defensively copied (caller may retain the array)
     */
    public RingFrame(FrameType type, byte[] body) {
        this(type, FrameBody.ofCopy(body, 0, body.length));
    }

    /**
     * Wrap {@code body} as a frame without copying — caller hands over ownership of the array.
     * Used by codecs whose {@code encode()} returns a fresh array.
     */
    public static RingFrame wrapping(FrameType type, byte[] body) {
        return new RingFrame(type, FrameBody.ofOwned(body));
    }

    /** Type-only frame (zero-length body) — reuses {@link FrameBody#empty()}, no allocation. */
    public static RingFrame ofType(FrameType type) {
        return new RingFrame(type, FrameBody.empty());
    }

    /**
     * Escape hatch for callers that genuinely need the body as a byte array (e.g. legacy
     * codecs). Allocates a fresh copy each invocation; NOT used on the hot dispatch / write
     * path. Hot-path consumers use {@code body().asReadOnlyBuffer()} or
     * {@code body().writeTo(dst)} instead.
     */
    public byte[] bodyBytes() {
        return body.copyToByteArray();
    }

    @Override
    public String toString() {
        return "RingFrame{type=" + type + ", " + body + "}";
    }
}

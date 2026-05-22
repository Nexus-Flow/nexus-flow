package net.nexus_flow.core.ring.wire;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Encodes {@link RingFrame} instances into the wire byte sequence.
 *
 * <p>Stateless and thread-safe. Two encode shapes:
 *
 * <ol>
 * <li>{@link #encode(RingFrame)} / {@link #encode(RingFrame, int)} — allocate a fresh
 * {@code byte[]} of exact length; convenience for tests and low-frequency frames.
 * <li>{@link #encodeInto(RingFrame, ByteBuffer, int)} — write into a caller-supplied
 * buffer; ZERO heap allocation on the hot path when the buffer is pooled per
 * connection.
 * </ol>
 *
 * <h2>Hot-path discipline</h2>
 *
 * The hot send path uses {@code encodeInto(...)} against a per-connection direct buffer
 * (allocated once at connection construction, reused across frames). The encoder does not
 * call the previous {@code RingFrame.body()} getter — instead it calls
 * {@link FrameBody#writeTo(ByteBuffer)}, which is a single bulk {@code put(byte[], int, int)}
 * with no per-byte allocation.
 */
public final class FrameEncoder {

    private FrameEncoder() {
    }

    /** Encode with the framework default body cap. */
    public static byte[] encode(RingFrame frame) {
        return encode(frame, RingProtocol.DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * Encode into a freshly-allocated {@code byte[]} of exact length.
     *
     * @throws FrameTooLargeException if the body exceeds {@code maxBodyBytes}
     */
    public static byte[] encode(RingFrame frame, int maxBodyBytes) {
        Objects.requireNonNull(frame, "frame");
        int bodyLen = frame.body().length();
        if (bodyLen > maxBodyBytes) {
            throw new FrameTooLargeException(bodyLen, maxBodyBytes);
        }
        byte[]     out = new byte[RingProtocol.HEADER_BYTES + bodyLen];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        writeHeader(buf, frame.type(), bodyLen);
        frame.body().writeTo(buf);
        return out;
    }

    /**
     * Encode into {@code dst} starting at its current position. Advances {@code dst.position}
     * past the written bytes. Caller is responsible for ensuring sufficient remaining capacity
     * — typically a per-connection pooled buffer cleared between frames.
     *
     * @throws FrameTooLargeException           if the body exceeds {@code maxBodyBytes}
     * @throws java.nio.BufferOverflowException if {@code dst} has insufficient remaining
     *                                          capacity
     */
    public static void encodeInto(RingFrame frame, ByteBuffer dst, int maxBodyBytes) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(dst, "dst");
        int bodyLen = frame.body().length();
        if (bodyLen > maxBodyBytes) {
            throw new FrameTooLargeException(bodyLen, maxBodyBytes);
        }
        int required = RingProtocol.HEADER_BYTES + bodyLen;
        if (dst.remaining() < required) {
            throw new java.nio.BufferOverflowException();
        }
        ByteOrder original = dst.order();
        dst.order(ByteOrder.BIG_ENDIAN);
        try {
            writeHeader(dst, frame.type(), bodyLen);
            frame.body().writeTo(dst);
        } finally {
            dst.order(original);
        }
    }

    /** Encoded length (header + body) of {@code frame} — for buffer pre-sizing. */
    public static int encodedLength(RingFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return RingProtocol.HEADER_BYTES + frame.body().length();
    }

    private static void writeHeader(ByteBuffer buf, FrameType type, int bodyLen) {
        buf.put(RingProtocol.MAGIC);
        buf.put(RingProtocol.VERSION);
        buf.put(type.wireCode());
        buf.putShort((short) 0);
        buf.putInt(bodyLen);
    }
}

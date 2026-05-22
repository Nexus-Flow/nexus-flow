package net.nexus_flow.core.ring.wire;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * {@link RingFrameCodec} implementation backed by JDK 22 {@link MemorySegment} bulk operations.
 *
 * <h2>What it actually does differently</h2>
 *
 * <p>The encode path wraps the caller-supplied {@link ByteBuffer} with
 * {@link MemorySegment#ofBuffer(ByteBuffer)} and writes the 12-byte header through
 * {@code MemorySegment.set(ValueLayout, offset, value)} accessors and the body bytes through
 * {@link MemorySegment#copy(MemorySegment, long, MemorySegment, long, long)} — the second of
 * which HotSpot lowers to an intrinsic {@code memmove} on the HotSpot 22+ tier. For the small
 * header that fits in a single cache line the two paths are equivalent; for body bytes larger
 * than ~256 bytes the intrinsic copy avoids the {@link ByteBuffer#put(byte[], int, int)}
 * bounds-check loop the {@link DefaultRingFrameCodec} path takes through {@link FrameBody}.
 *
 * <h2>When to select this codec</h2>
 *
 * <p>Wire-format compatible with {@link DefaultRingFrameCodec} byte-for-byte (the
 * {@link RingProtocol} reference encoding is the only legal output). Pick this codec when:
 *
 * <ul>
 * <li>Your frames consistently carry body sizes above ~256 bytes — the
 * {@code MemorySegment.copy} intrinsic amortises the small per-call setup cost.
 * <li>You have wired a per-connection direct {@link java.lang.foreign.Arena} pool and want to
 * encode into off-heap memory without going through {@link ByteBuffer#allocateDirect(int)}
 * on the hot path (set up the {@link MemorySegment} pool externally and pass its
 * {@link MemorySegment#asByteBuffer()} as the encode buffer).
 * </ul>
 *
 * <p>Defaults stay on {@link DefaultRingFrameCodec} because for small frames the virtual
 * dispatch + wrap cost outweighs the intrinsic copy benefit. See
 * {@code RingFrameCodecBenchmark} for the crossover data.
 *
 * <h2>Decoding</h2>
 *
 * <p>{@link #newDecoder(int)} returns the same {@link FrameDecoder} the default codec uses —
 * the read path's allocation profile is dominated by the {@code byte[]} body buffer for the
 * incrementally-grown body, not by header parsing. A MemorySegment-based decoder would need
 * a per-connection direct Arena to add value; that integration belongs in an adapter module.
 */
public final class MemorySegmentRingFrameCodec implements RingFrameCodec {

    /** Process-wide singleton — no per-runtime state. */
    public static final MemorySegmentRingFrameCodec INSTANCE = new MemorySegmentRingFrameCodec();

    private static final ValueLayout.OfByte  BYTE_LAYOUT     = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfShort SHORT_BE_LAYOUT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt   INT_BE_LAYOUT   =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private MemorySegmentRingFrameCodec() {
    }

    @Override
    public void encodeInto(RingFrame frame, ByteBuffer dst, int maxBodyBytes) {
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
        int           startPos = dst.position();
        MemorySegment dstSeg   = MemorySegment.ofBuffer(dst);
        writeHeader(dstSeg, startPos, frame.type(), bodyLen);
        if (bodyLen > 0) {
            MemorySegment bodySeg = frame.body().asMemorySegment();
            MemorySegment.copy(bodySeg, 0L, dstSeg, (long) startPos + RingProtocol.HEADER_BYTES, bodyLen);
        }
        dst.position(startPos + required);
    }

    @Override
    public byte[] encode(RingFrame frame, int maxBodyBytes) {
        Objects.requireNonNull(frame, "frame");
        int bodyLen = frame.body().length();
        if (bodyLen > maxBodyBytes) {
            throw new FrameTooLargeException(bodyLen, maxBodyBytes);
        }
        byte[]        out    = new byte[RingProtocol.HEADER_BYTES + bodyLen];
        MemorySegment dstSeg = MemorySegment.ofArray(out);
        writeHeader(dstSeg, 0, frame.type(), bodyLen);
        if (bodyLen > 0) {
            MemorySegment bodySeg = frame.body().asMemorySegment();
            MemorySegment.copy(bodySeg, 0L, dstSeg, (long) RingProtocol.HEADER_BYTES, bodyLen);
        }
        return out;
    }

    @Override
    public int encodedLength(RingFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return RingProtocol.HEADER_BYTES + frame.body().length();
    }

    @Override
    public FrameDecoder newDecoder(int maxBodyBytes) {
        return new FrameDecoder(maxBodyBytes);
    }

    private static void writeHeader(MemorySegment dst, int baseOffset, FrameType type, int bodyLen) {
        long off = baseOffset;
        for (int i = 0; i < RingProtocol.MAGIC_BYTES; i++) {
            dst.set(BYTE_LAYOUT, off + i, RingProtocol.MAGIC[i]);
        }
        off += RingProtocol.MAGIC_BYTES;
        dst.set(BYTE_LAYOUT, off, RingProtocol.VERSION);
        off++;
        dst.set(BYTE_LAYOUT, off, type.wireCode());
        off++;
        dst.set(SHORT_BE_LAYOUT, off, (short) 0);
        off += Short.BYTES;
        dst.set(INT_BE_LAYOUT, off, bodyLen);
    }
}

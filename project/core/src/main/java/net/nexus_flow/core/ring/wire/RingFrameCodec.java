package net.nexus_flow.core.ring.wire;

import java.nio.ByteBuffer;

/**
 * Pluggable wire codec for ring transport frames. The codec is chosen once at runtime
 * configuration time ({@link net.nexus_flow.core.ring.transport.RingAcceptorConfig} /
 * {@link net.nexus_flow.core.ring.transport.RingDialerConfig}) and reused for every frame
 * on every connection that the corresponding side opens.
 *
 * <h2>Why a strategy interface</h2>
 *
 * <p>The framework ships with a {@link #BYTE_BUFFER} implementation that uses {@code
 * ByteBuffer}-based bulk operations and is byte-for-byte compatible with every wire-format
 * test in the suite. Adapter modules can supply alternative implementations without
 * touching {@code core}:
 *
 * <ul>
 * <li>A {@code MemorySegment}-based codec that writes through {@code MemorySegment.copy}
 * intrinsics into a per-connection direct buffer (off-heap pool reuse, fewer JNI
 * crossings on the Linux/macOS hot path).
 * <li>A Netty {@code ByteBuf}-backed codec for processes already running a Netty event
 * loop, eliminating the {@code ByteBuf} ↔ {@code ByteBuffer} copy at the boundary.
 * <li>A test-only codec that records every encode/decode for protocol assertions.
 * </ul>
 *
 * <p>Implementations are stateless and thread-safe — the same {@code RingFrameCodec}
 * instance is shared by every connection on a runtime. Per-connection state lives in the
 * {@link FrameDecoder} that {@link #newDecoder(int)} returns.
 *
 * <h2>Wire compatibility</h2>
 *
 * <p>Every implementation must produce the exact byte sequence defined by {@link
 * RingProtocol} — magic + version + type + reserved-flags + body-length + body. Two peers
 * may run different codecs and still interoperate; the codec is purely an in-process
 * optimization choice.
 */
public interface RingFrameCodec {

    /**
     * Encode {@code frame} into {@code dst} starting at its current position. Advances
     * {@code dst.position} past the written bytes.
     *
     * @throws FrameTooLargeException           if the body exceeds {@code maxBodyBytes}
     * @throws java.nio.BufferOverflowException if {@code dst} has insufficient remaining
     *                                          capacity for the header + body
     */
    void encodeInto(RingFrame frame, ByteBuffer dst, int maxBodyBytes);

    /**
     * Encode {@code frame} into a freshly-allocated {@code byte[]} of exact length.
     * Intended for tests and low-frequency frames; the hot send path should use
     * {@link #encodeInto(RingFrame, ByteBuffer, int)} against a pooled per-connection
     * buffer.
     *
     * @throws FrameTooLargeException if the body exceeds {@code maxBodyBytes}
     */
    byte[] encode(RingFrame frame, int maxBodyBytes);

    /** Total wire length (header + body) of {@code frame} — used for buffer pre-sizing. */
    int encodedLength(RingFrame frame);

    /**
     * Construct a fresh stateful decoder bound to {@code maxBodyBytes}. The returned
     * decoder is single-owner: one decoder per connection, used exclusively by the
     * thread reading from that connection.
     */
    FrameDecoder newDecoder(int maxBodyBytes);

    /**
     * Default framework codec — {@code ByteBuffer}-based bulk encode/decode. Byte-for-byte
     * identical to the protocol's reference encoding and the implementation used by the
     * test suite. Selected when no alternative is supplied through configuration.
     */
    RingFrameCodec BYTE_BUFFER = DefaultRingFrameCodec.INSTANCE;

    /**
     * {@link java.lang.foreign.MemorySegment}-backed encoder. Bit-compatible with
     * {@link #BYTE_BUFFER}; uses {@link java.lang.foreign.MemorySegment#copy} for the body
     * copy so HotSpot 22+ lowers the bulk transfer to its {@code memmove} intrinsic. Wins
     * on frames whose body exceeds the ~256-byte crossover where the intrinsic amortises
     * its per-call setup; below the crossover the {@link #BYTE_BUFFER} default is faster
     * because the static call site avoids the virtual dispatch. See
     * {@code RingFrameCodecBenchmark} for the crossover data.
     */
    RingFrameCodec MEMORY_SEGMENT = MemorySegmentRingFrameCodec.INSTANCE;
}

package net.nexus_flow.core.ring.wire;

import java.nio.ByteBuffer;

/**
 * Default {@link RingFrameCodec}: thin adapter over the {@link FrameEncoder} static
 * helpers and {@code new FrameDecoder(maxBodyBytes)}. Stateless singleton.
 *
 * <p>This implementation is byte-for-byte compatible with the protocol reference and
 * is what the framework selects when no alternative codec is configured.
 */
public final class DefaultRingFrameCodec implements RingFrameCodec {

    /** Process-wide singleton — no per-runtime state. */
    public static final DefaultRingFrameCodec INSTANCE = new DefaultRingFrameCodec();

    private DefaultRingFrameCodec() {
    }

    @Override
    public void encodeInto(RingFrame frame, ByteBuffer dst, int maxBodyBytes) {
        FrameEncoder.encodeInto(frame, dst, maxBodyBytes);
    }

    @Override
    public byte[] encode(RingFrame frame, int maxBodyBytes) {
        return FrameEncoder.encode(frame, maxBodyBytes);
    }

    @Override
    public int encodedLength(RingFrame frame) {
        return FrameEncoder.encodedLength(frame);
    }

    @Override
    public FrameDecoder newDecoder(int maxBodyBytes) {
        return new FrameDecoder(maxBodyBytes);
    }
}

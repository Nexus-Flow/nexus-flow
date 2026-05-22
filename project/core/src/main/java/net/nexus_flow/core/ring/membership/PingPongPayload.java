package net.nexus_flow.core.ring.membership;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.wire.RingProtocol;
import net.nexus_flow.core.ring.wire.RingProtocolException;

/**
 * Body of a {@link net.nexus_flow.core.ring.wire.FrameType#PING PING} or {@link
 * net.nexus_flow.core.ring.wire.FrameType#PONG PONG} frame. The sequence number lets the
 * sender match a PONG to the originating PING; the sender peer id supports SWIM-style indirect
 * probes (a peer can know who is asking).
 *
 * <h2>Wire format (big-endian)</h2>
 *
 * <pre>
 * uint64 sequence
 * string senderPeerId (uint16 length-prefixed UTF-8)
 * </pre>
 */
public record PingPongPayload(long sequence, PeerId senderPeerId) {

    public PingPongPayload {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0: " + sequence);
        }
        Objects.requireNonNull(senderPeerId, "senderPeerId");
    }

    public byte[] encode() {
        byte[]     senderBytes = senderPeerId.value().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf         = ByteBuffer.allocate(8 + 2 + senderBytes.length).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(sequence);
        if (senderBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("senderPeerId exceeds uint16 cap");
        }
        buf.putShort((short) senderBytes.length);
        buf.put(senderBytes);
        return buf.array();
    }

    public static PingPongPayload decode(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            ByteBuffer buf      = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            long       sequence = buf.getLong();
            int        len      = buf.getShort() & 0xFFFF;
            if (len > RingProtocol.MAX_PEER_ID_BYTES) {
                throw new RingProtocolException(
                        "senderPeerId length " + len + " exceeds MAX_PEER_ID_BYTES");
            }
            byte[] senderBytes = new byte[len];
            buf.get(senderBytes);
            if (buf.hasRemaining()) {
                throw new RingProtocolException(
                        "trailing bytes in PingPongPayload body: " + buf.remaining());
            }
            return new PingPongPayload(
                    sequence, PeerId.of(new String(senderBytes, StandardCharsets.UTF_8)));
        } catch (RingProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RingProtocolException(
                    "malformed PingPongPayload body: " + e.getMessage(), e);
        }
    }
}

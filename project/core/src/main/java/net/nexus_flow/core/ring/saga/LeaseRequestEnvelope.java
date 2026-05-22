package net.nexus_flow.core.ring.saga;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.wire.RingProtocol;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import net.nexus_flow.core.saga.SagaId;

/**
 * Wire envelope for a {@link net.nexus_flow.core.ring.wire.FrameType#LEASE_REQ LEASE_REQ}
 * frame. A peer sends this when it observes that a saga's lease has expired and it wants to
 * claim ownership.
 *
 * <h2>Wire format (big-endian)</h2>
 *
 * <pre>
 * byte[16] sagaId (raw UUID)
 * string requesterPeerId (uint16 length-prefixed UTF-8)
 * uint64 requestedExpiryEpochMillis (the expiry the requester proposes for the new lease)
 * </pre>
 */
public record LeaseRequestEnvelope(
                                   SagaId sagaId, PeerId requesterPeerId, long requestedExpiryEpochMillis) {

    public LeaseRequestEnvelope {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(requesterPeerId, "requesterPeerId");
        if (requestedExpiryEpochMillis <= 0) {
            throw new IllegalArgumentException(
                    "requestedExpiryEpochMillis must be > 0: " + requestedExpiryEpochMillis);
        }
    }

    public byte[] encode() {
        byte[]     requesterBytes = requesterPeerId.value().getBytes(StandardCharsets.UTF_8);
        int        size           = 16 + 2 + requesterBytes.length + 8;
        ByteBuffer buf            = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        UUID       id             = sagaId.value();
        buf.putLong(id.getMostSignificantBits());
        buf.putLong(id.getLeastSignificantBits());
        if (requesterBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("requesterPeerId exceeds uint16 cap");
        }
        buf.putShort((short) requesterBytes.length);
        buf.put(requesterBytes);
        buf.putLong(requestedExpiryEpochMillis);
        return buf.array();
    }

    public static LeaseRequestEnvelope decode(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            UUID       id  = new UUID(buf.getLong(), buf.getLong());
            int        len = buf.getShort() & 0xFFFF;
            if (len > RingProtocol.MAX_PEER_ID_BYTES) {
                throw new RingProtocolException(
                        "requesterPeerId length " + len + " exceeds MAX_PEER_ID_BYTES");
            }
            byte[] requesterBytes = new byte[len];
            buf.get(requesterBytes);
            long expiry = buf.getLong();
            if (buf.hasRemaining()) {
                throw new RingProtocolException(
                        "trailing bytes in LeaseRequestEnvelope body: " + buf.remaining());
            }
            return new LeaseRequestEnvelope(
                    new SagaId(id),
                    PeerId.of(new String(requesterBytes, StandardCharsets.UTF_8)),
                    expiry);
        } catch (RingProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RingProtocolException(
                    "malformed LeaseRequestEnvelope body: " + e.getMessage(), e);
        }
    }
}

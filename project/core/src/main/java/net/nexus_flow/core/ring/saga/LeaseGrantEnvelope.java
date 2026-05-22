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
 * Wire envelope for a {@link net.nexus_flow.core.ring.wire.FrameType#LEASE_GRANT LEASE_GRANT}
 * frame. The current ownership authority for a saga sends this in response to a
 * {@link LeaseRequestEnvelope} to confirm the new ownership claim succeeded.
 *
 * <h2>Wire format (big-endian)</h2>
 *
 * <pre>
 * byte[16] sagaId (raw UUID)
 * string grantedToPeerId (uint16 length-prefixed UTF-8)
 * uint64 leaseExpiryEpochMillis
 * </pre>
 *
 * <p>A grant rejection is encoded as a separate frame — a {@link
 * net.nexus_flow.core.ring.wire.FrameType#SAGA_STATE SAGA_STATE} pointing at the current
 * owner — so the requester can update its local view directly.
 */
public record LeaseGrantEnvelope(
                                 SagaId sagaId, PeerId grantedToPeerId, long leaseExpiryEpochMillis) {

    public LeaseGrantEnvelope {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(grantedToPeerId, "grantedToPeerId");
        if (leaseExpiryEpochMillis <= 0) {
            throw new IllegalArgumentException(
                    "leaseExpiryEpochMillis must be > 0: " + leaseExpiryEpochMillis);
        }
    }

    public byte[] encode() {
        byte[]     granteeBytes = grantedToPeerId.value().getBytes(StandardCharsets.UTF_8);
        int        size         = 16 + 2 + granteeBytes.length + 8;
        ByteBuffer buf          = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        UUID       id           = sagaId.value();
        buf.putLong(id.getMostSignificantBits());
        buf.putLong(id.getLeastSignificantBits());
        if (granteeBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("grantedToPeerId exceeds uint16 cap");
        }
        buf.putShort((short) granteeBytes.length);
        buf.put(granteeBytes);
        buf.putLong(leaseExpiryEpochMillis);
        return buf.array();
    }

    public static LeaseGrantEnvelope decode(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            UUID       id  = new UUID(buf.getLong(), buf.getLong());
            int        len = buf.getShort() & 0xFFFF;
            if (len > RingProtocol.MAX_PEER_ID_BYTES) {
                throw new RingProtocolException(
                        "grantedToPeerId length " + len + " exceeds MAX_PEER_ID_BYTES");
            }
            byte[] granteeBytes = new byte[len];
            buf.get(granteeBytes);
            long expiry = buf.getLong();
            if (buf.hasRemaining()) {
                throw new RingProtocolException(
                        "trailing bytes in LeaseGrantEnvelope body: " + buf.remaining());
            }
            return new LeaseGrantEnvelope(
                    new SagaId(id),
                    PeerId.of(new String(granteeBytes, StandardCharsets.UTF_8)),
                    expiry);
        } catch (RingProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RingProtocolException(
                    "malformed LeaseGrantEnvelope body: " + e.getMessage(), e);
        }
    }
}

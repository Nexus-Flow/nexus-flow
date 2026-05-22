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
 * Wire envelope for a {@link net.nexus_flow.core.ring.wire.FrameType#SAGA_STATE SAGA_STATE}
 * frame. Serves three roles:
 *
 * <ol>
 * <li><strong>Periodic heartbeat</strong> — the current owner broadcasts every renewal
 * interval to refresh the lease across the ring.
 * <li><strong>Ownership change announcement</strong> — when a peer claims an expired
 * lease, it broadcasts SAGA_STATE with itself as the new owner so other peers update
 * their {@link LeaseRegistry} accordingly.
 * <li><strong>LEASE_REQ rejection</strong> — if a peer receives a {@link
 * LeaseRequestEnvelope LEASE_REQ} but the lease has already been claimed by someone
 * else, the reply is a SAGA_STATE pointing at the current authority.
 * </ol>
 *
 * <h2>Wire format (big-endian)</h2>
 *
 * <pre>
 * byte[16] sagaId (raw UUID)
 * string ownerPeerId (uint16 length-prefixed UTF-8)
 * uint64 leaseExpiryEpochMillis
 * uint64 stateVersion (matches SagaState.version() for ordering / split-brain detection)
 * </pre>
 */
public record SagaStateEnvelope(
                                SagaId sagaId, PeerId ownerPeerId, long leaseExpiryEpochMillis, long stateVersion) {

    public SagaStateEnvelope {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(ownerPeerId, "ownerPeerId");
        if (leaseExpiryEpochMillis <= 0) {
            throw new IllegalArgumentException(
                    "leaseExpiryEpochMillis must be > 0: " + leaseExpiryEpochMillis);
        }
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0: " + stateVersion);
        }
    }

    public byte[] encode() {
        byte[] ownerBytes = ownerPeerId.value().getBytes(StandardCharsets.UTF_8);
        if (ownerBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("ownerPeerId exceeds uint16 cap");
        }
        ByteBuffer buf = ByteBuffer.allocate(16 + 2 + ownerBytes.length + 8 + 8)
                .order(ByteOrder.BIG_ENDIAN);
        UUID       id  = sagaId.value();
        buf.putLong(id.getMostSignificantBits());
        buf.putLong(id.getLeastSignificantBits());
        buf.putShort((short) ownerBytes.length);
        buf.put(ownerBytes);
        buf.putLong(leaseExpiryEpochMillis);
        buf.putLong(stateVersion);
        return buf.array();
    }

    public static SagaStateEnvelope decode(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            ByteBuffer buf      = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            UUID       id       = new UUID(buf.getLong(), buf.getLong());
            int        ownerLen = buf.getShort() & 0xFFFF;
            if (ownerLen > RingProtocol.MAX_PEER_ID_BYTES) {
                throw new RingProtocolException(
                        "ownerPeerId length " + ownerLen + " exceeds MAX_PEER_ID_BYTES");
            }
            byte[] ownerBytes = new byte[ownerLen];
            buf.get(ownerBytes);
            long expiry  = buf.getLong();
            long version = buf.getLong();
            if (buf.hasRemaining()) {
                throw new RingProtocolException(
                        "trailing bytes in SagaStateEnvelope body: " + buf.remaining());
            }
            return new SagaStateEnvelope(
                    new SagaId(id),
                    PeerId.of(new String(ownerBytes, StandardCharsets.UTF_8)),
                    expiry,
                    version);
        } catch (RingProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RingProtocolException(
                    "malformed SagaStateEnvelope body: " + e.getMessage(), e);
        }
    }
}

package net.nexus_flow.core.ring.event;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.wire.RingProtocol;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import org.jspecify.annotations.Nullable;

/**
 * Wire envelope for one domain event crossing a peer boundary. Carried in the body of a
 * {@link net.nexus_flow.core.ring.wire.FrameType#EVENT EVENT} frame.
 *
 * <h2>Field layout (body of an EVENT frame, big-endian)</h2>
 *
 * <pre>
 * string sourcePeerId (uint16 length-prefixed UTF-8)
 * uint64 sourceOutboxSequence
 * string payloadType (uint16 length-prefixed UTF-8)
 * string codecId (uint16 length-prefixed UTF-8)
 * byte[16] traceId (raw 128-bit UUID, MSB then LSB)
 * byte[16] correlationId (raw 128-bit UUID)
 * byte[16] causationId (raw 128-bit UUID)
 * string tenantId (uint16 length-prefixed UTF-8; empty string = null)
 * uint32 payloadBytes length
 * byte[] payloadBytes
 * </pre>
 *
 * <p>Defensive caps:
 *
 * <ul>
 * <li>{@code sourcePeerId} ≤ {@link RingProtocol#MAX_PEER_ID_BYTES}
 * <li>{@code payloadType} ≤ {@link RingProtocol#MAX_TYPE_NAME_BYTES}
 * <li>{@code codecId} ≤ {@link RingProtocol#MAX_TYPE_NAME_BYTES}
 * <li>{@code tenantId} ≤ {@link RingProtocol#MAX_TENANT_ID_BYTES}
 * <li>{@code payloadBytes.length} ≤ {@link #MAX_PAYLOAD_BYTES}, plus the outer frame's
 * {@link net.nexus_flow.core.ring.wire.FrameDecoder} cap (whichever is stricter)
 * </ul>
 */
public record RingEventEnvelope(
                                PeerId sourcePeerId,
                                long sourceOutboxSequence,
                                String payloadType,
                                String codecId,
                                UUID traceId,
                                UUID correlationId,
                                UUID causationId,
                                @Nullable String tenantId,
                                byte[] payloadBytes) {

    /**
     * Defensive cap on the embedded payload size. The actual frame-level cap (configured per
     * {@link net.nexus_flow.core.ring.wire.FrameDecoder}) is typically stricter — this value
     * is the wire-format ceiling that a malicious or buggy peer cannot encode past because
     * the length field is uint32 (capped here to {@link Integer#MAX_VALUE}).
     */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

    /** Compact constructor — defensive null checks, length caps, payload defensive copy. */
    public RingEventEnvelope {
        Objects.requireNonNull(sourcePeerId, "sourcePeerId");
        if (sourceOutboxSequence < 0) {
            throw new IllegalArgumentException(
                    "sourceOutboxSequence must be >= 0: " + sourceOutboxSequence);
        }
        Objects.requireNonNull(payloadType, "payloadType");
        if (payloadType.isEmpty()) {
            throw new IllegalArgumentException("payloadType must not be empty");
        }
        if (payloadType.getBytes(StandardCharsets.UTF_8).length > RingProtocol.MAX_TYPE_NAME_BYTES) {
            throw new IllegalArgumentException(
                    "payloadType UTF-8 length exceeds MAX_TYPE_NAME_BYTES = "
                            + RingProtocol.MAX_TYPE_NAME_BYTES);
        }
        Objects.requireNonNull(codecId, "codecId");
        if (codecId.getBytes(StandardCharsets.UTF_8).length > RingProtocol.MAX_TYPE_NAME_BYTES) {
            throw new IllegalArgumentException(
                    "codecId UTF-8 length exceeds MAX_TYPE_NAME_BYTES = "
                            + RingProtocol.MAX_TYPE_NAME_BYTES);
        }
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        if (tenantId != null && tenantId.getBytes(StandardCharsets.UTF_8).length > RingProtocol.MAX_TENANT_ID_BYTES) {
            throw new IllegalArgumentException(
                    "tenantId UTF-8 length exceeds MAX_TENANT_ID_BYTES");
        }
        Objects.requireNonNull(payloadBytes, "payloadBytes");
        if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "payloadBytes length "
                            + payloadBytes.length
                            + " exceeds MAX_PAYLOAD_BYTES = "
                            + MAX_PAYLOAD_BYTES);
        }
        payloadBytes = payloadBytes.clone();
    }

    /** Returns the defensive copy of the payload bytes. */
    @Override
    public byte[] payloadBytes() {
        return payloadBytes.clone();
    }

    /**
     * Serialise this envelope into the body bytes of an EVENT frame.
     *
     * @return the encoded body bytes
     */
    public byte[] encode() {
        byte[]     sourcePeerBytes = sourcePeerId.value().getBytes(StandardCharsets.UTF_8);
        byte[]     typeBytes       = payloadType.getBytes(StandardCharsets.UTF_8);
        byte[]     codecBytes      = codecId.getBytes(StandardCharsets.UTF_8);
        byte[]     tenantBytes     =
                tenantId == null ? new byte[0] : tenantId.getBytes(StandardCharsets.UTF_8);
        int        size            =
                2 + sourcePeerBytes.length + 8 // sourceOutboxSequence
                        + 2 + typeBytes.length + 2 + codecBytes.length + 16 + 16 + 16 // three UUIDs
                        + 2 + tenantBytes.length + 4 + payloadBytes.length;
        ByteBuffer buf             = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        writeString(buf, sourcePeerBytes);
        buf.putLong(sourceOutboxSequence);
        writeString(buf, typeBytes);
        writeString(buf, codecBytes);
        writeUuid(buf, traceId);
        writeUuid(buf, correlationId);
        writeUuid(buf, causationId);
        writeString(buf, tenantBytes);
        buf.putInt(payloadBytes.length);
        buf.put(payloadBytes);
        return buf.array();
    }

    /**
     * Deserialise an envelope from EVENT frame body bytes.
     *
     * @param body the frame body; must not be {@code null}
     * @return the decoded envelope
     * @throws RingProtocolException if the body does not parse or violates a defensive cap
     */
    public static RingEventEnvelope decode(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            ByteBuffer buf         = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            String     sourcePeer  = readString(buf, RingProtocol.MAX_PEER_ID_BYTES);
            long       sequence    = buf.getLong();
            String     type        = readString(buf, RingProtocol.MAX_TYPE_NAME_BYTES);
            String     codec       = readString(buf, RingProtocol.MAX_TYPE_NAME_BYTES);
            UUID       trace       = readUuid(buf);
            UUID       correlation = readUuid(buf);
            UUID       causation   = readUuid(buf);
            String     tenantRaw   = readString(buf, RingProtocol.MAX_TENANT_ID_BYTES);
            String     tenant      = tenantRaw.isEmpty() ? null : tenantRaw;
            int        payloadLen  = buf.getInt();
            if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {
                throw new RingProtocolException(
                        "payload length out of bounds: " + payloadLen);
            }
            if (payloadLen != buf.remaining()) {
                throw new RingProtocolException(
                        "payload length "
                                + payloadLen
                                + " does not match remaining bytes "
                                + buf.remaining());
            }
            byte[] payload = new byte[payloadLen];
            buf.get(payload);
            return new RingEventEnvelope(
                    PeerId.of(sourcePeer),
                    sequence,
                    type,
                    codec,
                    trace,
                    correlation,
                    causation,
                    tenant,
                    payload);
        } catch (RingProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RingProtocolException(
                    "malformed RingEventEnvelope body: " + e.getMessage(), e);
        }
    }

    private static void writeString(ByteBuffer buf, byte[] bytes) {
        if (bytes.length > 0xFFFF) {
            throw new IllegalArgumentException("string length exceeds uint16 cap: " + bytes.length);
        }
        buf.putShort((short) bytes.length);
        buf.put(bytes);
    }

    private static String readString(ByteBuffer buf, int maxBytes) {
        int len = buf.getShort() & 0xFFFF;
        if (len > maxBytes) {
            throw new RingProtocolException("string length " + len + " exceeds cap " + maxBytes);
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeUuid(ByteBuffer buf, UUID uuid) {
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buf) {
        long msb = buf.getLong();
        long lsb = buf.getLong();
        return new UUID(msb, lsb);
    }
}

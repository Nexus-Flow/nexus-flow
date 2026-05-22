package net.nexus_flow.core.ring.dispatch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.wire.RingProtocol;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import org.jspecify.annotations.Nullable;

/**
 * Wire envelope for a cross-pod command or query request.
 *
 * <h2>Wire format (body of the request frame, big-endian)</h2>
 *
 * <pre>
 * byte role discriminator (0x01 = COMMAND, 0x02 = QUERY)
 * byte[16] correlationId (raw UUID, MSB then LSB)
 * string sourcePeerId (uint16 length-prefixed UTF-8)
 * string payloadType (uint16 length-prefixed UTF-8)
 * string codecId (uint16 length-prefixed UTF-8)
 * byte[16] traceId
 * byte[16] correlationCtxId
 * byte[16] causationId
 * string tenantId (uint16 length-prefixed UTF-8; empty = null)
 * uint64 sendInstantEpochMillis (sender wall-clock at send time; diagnostic only)
 * uint64 deadlineRemainingMillis (0 = no deadline; otherwise sender's
 * remaining-budget-from-send-time)
 * uint32 payloadBytes length
 * byte[] payloadBytes
 * </pre>
 *
 * <h2>Why two timestamp fields instead of one absolute epoch</h2>
 *
 * The audit identified that the previous shape (a single
 * {@code deadlineEpochMillis}) silently failed under clock skew — a receiver with a clock
 * 5 s ahead of the sender would treat every command as already expired. The receiver should
 * convert the deadline to its OWN monotonic clock as soon as the frame is decoded. The
 * sender packs the relative remaining-budget (millis from {@code sendInstantEpochMillis}),
 * which the receiver adds to its local {@code Clock} reading; the absolute send timestamp is
 * preserved only for diagnostics and for adapters that want to record one-way latency.
 */
public record DispatchRequestEnvelope(
                                      HandlerRole role,
                                      DispatchCorrelationId correlationId,
                                      PeerId sourcePeerId,
                                      String payloadType,
                                      String codecId,
                                      UUID traceId,
                                      UUID contextCorrelationId,
                                      UUID causationId,
                                      @Nullable String tenantId,
                                      long sendInstantEpochMillis,
                                      long deadlineRemainingMillis,
                                      byte[] payloadBytes) {

    public static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

    public DispatchRequestEnvelope {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(sourcePeerId, "sourcePeerId");
        Objects.requireNonNull(payloadType, "payloadType");
        if (payloadType.isEmpty()) {
            throw new IllegalArgumentException("payloadType must not be empty");
        }
        if (payloadType.getBytes(StandardCharsets.UTF_8).length > RingProtocol.MAX_TYPE_NAME_BYTES) {
            throw new IllegalArgumentException("payloadType exceeds MAX_TYPE_NAME_BYTES");
        }
        Objects.requireNonNull(codecId, "codecId");
        if (codecId.getBytes(StandardCharsets.UTF_8).length > RingProtocol.MAX_TYPE_NAME_BYTES) {
            throw new IllegalArgumentException("codecId exceeds MAX_TYPE_NAME_BYTES");
        }
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(contextCorrelationId, "contextCorrelationId");
        Objects.requireNonNull(causationId, "causationId");
        if (tenantId != null && tenantId.getBytes(StandardCharsets.UTF_8).length > RingProtocol.MAX_TENANT_ID_BYTES) {
            throw new IllegalArgumentException("tenantId exceeds MAX_TENANT_ID_BYTES");
        }
        if (sendInstantEpochMillis < 0) {
            throw new IllegalArgumentException("sendInstantEpochMillis must be >= 0");
        }
        if (deadlineRemainingMillis < 0) {
            throw new IllegalArgumentException(
                    "deadlineRemainingMillis must be >= 0 (0 = no deadline)");
        }
        Objects.requireNonNull(payloadBytes, "payloadBytes");
        if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payloadBytes length exceeds MAX_PAYLOAD_BYTES");
        }
        payloadBytes = payloadBytes.clone();
    }

    @Override
    public byte[] payloadBytes() {
        return payloadBytes.clone();
    }

    /** {@code true} when the sender did not specify a deadline. */
    public boolean hasNoDeadline() {
        return deadlineRemainingMillis == 0L;
    }

    /**
     * Encode to the body bytes of a COMMAND_REQ / QUERY_REQ frame. Returns a freshly
     * allocated byte array sized exactly to the encoded length — no over-allocation.
     */
    public byte[] encode() {
        byte[]     sourcePeerBytes = sourcePeerId.value().getBytes(StandardCharsets.UTF_8);
        byte[]     typeBytes       = payloadType.getBytes(StandardCharsets.UTF_8);
        byte[]     codecBytes      = codecId.getBytes(StandardCharsets.UTF_8);
        byte[]     tenantBytes     = tenantId == null ? new byte[0] : tenantId.getBytes(StandardCharsets.UTF_8);
        int        size            =
                1 + 16 + 2 + sourcePeerBytes.length + 2 + typeBytes.length + 2 + codecBytes.length + 16 + 16 + 16 + 2 + tenantBytes.length + 8 + 8 + 4 + payloadBytes.length;
        ByteBuffer buf             = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.put(roleWireCode(role));
        writeUuid(buf, correlationId.value());
        writeString(buf, sourcePeerBytes);
        writeString(buf, typeBytes);
        writeString(buf, codecBytes);
        writeUuid(buf, traceId);
        writeUuid(buf, contextCorrelationId);
        writeUuid(buf, causationId);
        writeString(buf, tenantBytes);
        buf.putLong(sendInstantEpochMillis);
        buf.putLong(deadlineRemainingMillis);
        buf.putInt(payloadBytes.length);
        buf.put(payloadBytes);
        return buf.array();
    }

    public static DispatchRequestEnvelope decode(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            ByteBuffer  buf            = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            byte        roleCode       = buf.get();
            HandlerRole role           = roleFromWireCode(roleCode);
            UUID        correlation    = readUuid(buf);
            String      sourcePeer     = readString(buf, RingProtocol.MAX_PEER_ID_BYTES);
            String      type           = readString(buf, RingProtocol.MAX_TYPE_NAME_BYTES);
            String      codec          = readString(buf, RingProtocol.MAX_TYPE_NAME_BYTES);
            UUID        trace          = readUuid(buf);
            UUID        ctxCorrelation = readUuid(buf);
            UUID        causation      = readUuid(buf);
            String      tenantRaw      = readString(buf, RingProtocol.MAX_TENANT_ID_BYTES);
            String      tenant         = tenantRaw.isEmpty() ? null : tenantRaw;
            long        sendInstant    = buf.getLong();
            long        remaining      = buf.getLong();
            int         payloadLen     = buf.getInt();
            if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {
                throw new RingProtocolException("payload length out of bounds: " + payloadLen);
            }
            if (payloadLen != buf.remaining()) {
                throw new RingProtocolException(
                        "payload length " + payloadLen + " does not match remaining bytes "
                                + buf.remaining());
            }
            byte[] payload = new byte[payloadLen];
            buf.get(payload);
            return new DispatchRequestEnvelope(
                    role,
                    new DispatchCorrelationId(correlation),
                    PeerId.of(sourcePeer),
                    type,
                    codec,
                    trace,
                    ctxCorrelation,
                    causation,
                    tenant,
                    sendInstant,
                    remaining,
                    payload);
        } catch (RingProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RingProtocolException(
                    "malformed DispatchRequestEnvelope body: " + e.getMessage(), e);
        }
    }

    private static byte roleWireCode(HandlerRole role) {
        return switch (role) {
            case COMMAND -> (byte) 0x01;
            case QUERY   -> (byte) 0x02;
        };
    }

    private static HandlerRole roleFromWireCode(byte code) {
        return switch (code) {
            case 0x01 -> HandlerRole.COMMAND;
            case 0x02 -> HandlerRole.QUERY;
            default   -> throw new RingProtocolException(
                    String.format("unknown role code 0x%02X", code & 0xFF));
        };
    }

    private static void writeString(ByteBuffer buf, byte[] bytes) {
        if (bytes.length > 0xFFFF) {
            throw new IllegalArgumentException("string exceeds uint16 cap: " + bytes.length);
        }
        buf.putShort((short) bytes.length);
        buf.put(bytes);
    }

    private static String readString(ByteBuffer buf, int maxBytes) {
        int len = buf.getShort() & 0xFFFF;
        if (len > maxBytes) {
            throw new RingProtocolException(
                    "string length " + len + " exceeds cap " + maxBytes);
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
        return new UUID(buf.getLong(), buf.getLong());
    }
}

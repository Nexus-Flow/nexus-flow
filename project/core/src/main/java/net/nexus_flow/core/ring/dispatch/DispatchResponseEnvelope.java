package net.nexus_flow.core.ring.dispatch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.ring.wire.ProtocolErrorCode;
import net.nexus_flow.core.ring.wire.RingProtocol;
import net.nexus_flow.core.ring.wire.RingProtocolException;

/**
 * Wire envelope for a cross-pod command/query response.
 *
 * <h2>Sanitised error model (audit finding #15)</h2>
 *
 * The previous envelope echoed {@code exception.getMessage()} from the local handler back to
 * the remote peer. That was an information-disclosure gap — stack-trace fragments, internal
 * class names, bean names, and file paths leaked over the wire. The redesigned envelope
 * carries:
 *
 * <ul>
 * <li>A {@link ProtocolErrorCode} — small enum, frozen wire codes, no diagnostic content.
 * <li>An opaque correlation id — already present; the originator looks up rich diagnostic
 * detail in its own local logs against the correlation id.
 * <li>An optional short, sanitised {@code reason} string — capped at 200 chars and
 * intended for HUMAN summaries like {@code "tenant policy denied"}, never for raw
 * exception messages.
 * </ul>
 *
 * <h2>Wire format (body of the response frame, big-endian)</h2>
 *
 * <pre>
 * byte[16] correlationId
 * byte outcome code (see {@link Outcome#wireCode()})
 * byte errorCode (see {@link ProtocolErrorCode#wireCode()})
 * string payloadType (uint16 length-prefixed UTF-8; empty for outcomes with no body)
 * string codecId
 * string reason (uint16 length-prefixed UTF-8; empty when not applicable; max 200 chars)
 * uint32 payloadBytes length
 * byte[] payloadBytes
 * </pre>
 */
public record DispatchResponseEnvelope(
                                       DispatchCorrelationId correlationId,
                                       Outcome outcome,
                                       ProtocolErrorCode errorCode,
                                       String payloadType,
                                       String codecId,
                                       String reason,
                                       byte[] payloadBytes) {

    public static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    /** Sanitisation cap for {@link #reason()} — short enough that operators read it; long enough for context. */
    public static final int MAX_REASON_CHARS = 200;

    /** Sum of possible response outcomes. Wire codes are frozen — never reuse. */
    public enum Outcome {
        SUCCESS((byte) 0x01),
        FAILURE((byte) 0x02),
        PARTIAL_FAILURE((byte) 0x03),
        ACCEPTED((byte) 0x04),
        NOT_FOUND((byte) 0x10),
        TIMEOUT((byte) 0x11),
        /** Authorization layer refused the dispatch. */
        FORBIDDEN((byte) 0x12),
        /** Peer is draining / shutting down. */
        UNAVAILABLE((byte) 0x13);

        private final byte wireCode;

        Outcome(byte wireCode) {
            this.wireCode = wireCode;
        }

        public byte wireCode() {
            return wireCode;
        }

        public static Outcome fromWireCode(byte code) {
            for (Outcome o : values()) {
                if (o.wireCode == code) {
                    return o;
                }
            }
            throw new RingProtocolException(
                    String.format("unknown dispatch response outcome code 0x%02X", code & 0xFF));
        }
    }

    public DispatchResponseEnvelope {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(payloadType, "payloadType");
        Objects.requireNonNull(codecId, "codecId");
        Objects.requireNonNull(reason, "reason");
        if (payloadType.getBytes(StandardCharsets.UTF_8).length > RingProtocol.MAX_TYPE_NAME_BYTES) {
            throw new IllegalArgumentException("payloadType exceeds MAX_TYPE_NAME_BYTES");
        }
        if (codecId.getBytes(StandardCharsets.UTF_8).length > RingProtocol.MAX_TYPE_NAME_BYTES) {
            throw new IllegalArgumentException("codecId exceeds MAX_TYPE_NAME_BYTES");
        }
        if (reason.length() > MAX_REASON_CHARS) {
            throw new IllegalArgumentException(
                    "reason length " + reason.length() + " exceeds MAX_REASON_CHARS = " + MAX_REASON_CHARS);
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

    public static DispatchResponseEnvelope success(
            DispatchCorrelationId id, String payloadType, String codecId, byte[] body) {
        return new DispatchResponseEnvelope(
                id, Outcome.SUCCESS, ProtocolErrorCode.OK, payloadType, codecId, "", body);
    }

    public static DispatchResponseEnvelope failure(
            DispatchCorrelationId id, ProtocolErrorCode code, String sanitisedReason) {
        return new DispatchResponseEnvelope(
                id, Outcome.FAILURE, code, "", "", trim(sanitisedReason), new byte[0]);
    }

    public static DispatchResponseEnvelope partialFailure(
            DispatchCorrelationId id, String payloadType, String codecId, byte[] body,
            String sanitisedReason) {
        return new DispatchResponseEnvelope(
                id, Outcome.PARTIAL_FAILURE, ProtocolErrorCode.PARTIAL_FAILURE,
                payloadType, codecId, trim(sanitisedReason), body);
    }

    public static DispatchResponseEnvelope notFound(DispatchCorrelationId id, String detail) {
        return new DispatchResponseEnvelope(
                id, Outcome.NOT_FOUND, ProtocolErrorCode.NOT_FOUND,
                "", "", trim(detail), new byte[0]);
    }

    public static DispatchResponseEnvelope timeout(DispatchCorrelationId id, String detail) {
        return new DispatchResponseEnvelope(
                id, Outcome.TIMEOUT, ProtocolErrorCode.DEADLINE_EXCEEDED,
                "", "", trim(detail), new byte[0]);
    }

    public static DispatchResponseEnvelope forbidden(DispatchCorrelationId id, String shortReason) {
        return new DispatchResponseEnvelope(
                id, Outcome.FORBIDDEN, ProtocolErrorCode.FORBIDDEN,
                "", "", trim(shortReason), new byte[0]);
    }

    public static DispatchResponseEnvelope unavailable(DispatchCorrelationId id, String shortReason) {
        return new DispatchResponseEnvelope(
                id, Outcome.UNAVAILABLE, ProtocolErrorCode.UNAVAILABLE,
                "", "", trim(shortReason), new byte[0]);
    }

    public static DispatchResponseEnvelope accepted(
            DispatchCorrelationId id, String payloadType, String codecId, byte[] body) {
        return new DispatchResponseEnvelope(
                id, Outcome.ACCEPTED, ProtocolErrorCode.OK, payloadType, codecId, "", body);
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_REASON_CHARS ? s : s.substring(0, MAX_REASON_CHARS);
    }

    public byte[] encode() {
        byte[]     typeBytes   = payloadType.getBytes(StandardCharsets.UTF_8);
        byte[]     codecBytes  = codecId.getBytes(StandardCharsets.UTF_8);
        byte[]     reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        int        size        =
                16 + 1 + 1 + 2 + typeBytes.length + 2 + codecBytes.length + 2 + reasonBytes.length + 4 + payloadBytes.length;
        ByteBuffer buf         = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        UUID       corr        = correlationId.value();
        buf.putLong(corr.getMostSignificantBits());
        buf.putLong(corr.getLeastSignificantBits());
        buf.put(outcome.wireCode());
        buf.put(errorCode.wireCode());
        writeString(buf, typeBytes);
        writeString(buf, codecBytes);
        writeString(buf, reasonBytes);
        buf.putInt(payloadBytes.length);
        buf.put(payloadBytes);
        return buf.array();
    }

    public static DispatchResponseEnvelope decode(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            ByteBuffer        buf        = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            UUID              corr       = new UUID(buf.getLong(), buf.getLong());
            Outcome           outcome    = Outcome.fromWireCode(buf.get());
            ProtocolErrorCode errorCode  = ProtocolErrorCode.fromWireCode(buf.get());
            String            type       = readString(buf, RingProtocol.MAX_TYPE_NAME_BYTES);
            String            codec      = readString(buf, RingProtocol.MAX_TYPE_NAME_BYTES);
            String            reason     = readString(buf, MAX_REASON_CHARS * 4); // 4 bytes/char UTF-8 cap
            int               payloadLen = buf.getInt();
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
            return new DispatchResponseEnvelope(
                    new DispatchCorrelationId(corr),
                    outcome, errorCode, type, codec, reason, payload);
        } catch (RingProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RingProtocolException(
                    "malformed DispatchResponseEnvelope body: " + e.getMessage(), e);
        }
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
}

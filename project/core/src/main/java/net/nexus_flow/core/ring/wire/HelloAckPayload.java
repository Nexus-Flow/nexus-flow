package net.nexus_flow.core.ring.wire;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Listener's response to a {@link HelloPayload} — either acceptance ({@link Decision#ACCEPT}) or
 * a structured rejection with a documented {@link Decision} discriminator and a human-readable
 * diagnostic message.
 *
 * <h2>Wire format (body of a HELLO_ACK frame)</h2>
 *
 * <pre>
 * ubyte decisionCode (see {@link Decision#wireCode()})
 * string responderPeerId (uint16 length-prefixed UTF-8; empty for early rejection)
 * string message (uint16 length-prefixed UTF-8; empty when no diagnostic)
 * </pre>
 *
 * <h2>Why a discriminator and a message</h2>
 *
 * The discriminator lets the dialer take programmatic action: retry on
 * {@link Decision#TRANSIENT_REJECT}, give up on {@link Decision#VERSION_MISMATCH} (no point
 * retrying with the same binary), escalate to operations on
 * {@link Decision#FINGERPRINT_MISMATCH} (likely a deploy that pushed schema changes without
 * coordinating). The message is for operator triage in logs and dashboards.
 *
 * @param decision        the listener's outcome
 * @param responderPeerId the listener's peer id; never {@code null}, may be empty when the
 *                        rejection happens before the listener has read the dialer's hello
 * @param message         diagnostic; never {@code null}, may be empty
 */
public record HelloAckPayload(Decision decision, String responderPeerId, String message) {

    /**
     * Outcomes the listener can return on {@code HELLO}. Wire codes are stable for the life of
     * the protocol — never reuse a retired code.
     */
    public enum Decision {
        /** Connection accepted. The peers may exchange any frame type next. */
        ACCEPT((byte) 0x00),

        /**
         * Dialer's wire version does not match the listener's. Dialer SHOULD NOT retry with the
         * same binary — wait for a coordinated upgrade.
         */
        VERSION_MISMATCH((byte) 0x10),

        /**
         * One or more {@code typeName → fingerprint} pairs in the dialer's hello disagree with the
         * listener's view. The cluster has a schema-drift split-brain; an operator must investigate
         * (typically: a partial deploy is in flight).
         */
        FINGERPRINT_MISMATCH((byte) 0x20),

        /**
         * Dialer's tenant scope is incompatible with the listener's deployment policy (e.g. the
         * listener serves only tenant {@code "acme"} and the dialer announced tenant {@code "umbra"}).
         */
        TENANT_SCOPE_MISMATCH((byte) 0x30),

        /**
         * Dialer's peer id is already in use by another active connection — likely a misconfigured
         * peer id (two pods sharing the same id) or a stale connection that the listener has not
         * yet detected as dead.
         */
        PEER_ID_CONFLICT((byte) 0x40),

        /**
         * Listener is at its connection capacity. Dialer MAY retry after backoff once gossip
         * indicates capacity has freed up.
         */
        CAPACITY_EXCEEDED((byte) 0x50),

        /**
         * Generic transient rejection (transient resource shortage, partial init). Dialer MAY
         * retry with backoff. Avoid using this for anything an operator should actually investigate
         * — prefer a more specific code so dashboards can discriminate.
         */
        TRANSIENT_REJECT((byte) 0x60);

        private final byte wireCode;

        Decision(byte wireCode) {
            this.wireCode = wireCode;
        }

        /** @return the stable byte that represents this decision on the wire */
        public byte wireCode() {
            return wireCode;
        }

        /**
         * @param wireCode the byte read from the {@code HELLO_ACK} body
         * @return the matching decision, or {@code null} if the code is unknown
         */
        public static @Nullable Decision fromWireCode(byte wireCode) {
            for (Decision d : values()) {
                if (d.wireCode == wireCode)
                    return d;
            }
            return null;
        }
    }

    /** Compact constructor: validation and length caps. */
    public HelloAckPayload {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(responderPeerId, "responderPeerId");
        Objects.requireNonNull(message, "message");
        if (responderPeerId.getBytes(StandardCharsets.UTF_8).length > RingProtocol.MAX_PEER_ID_BYTES) {
            throw new IllegalArgumentException(
                    "responderPeerId UTF-8 length exceeds MAX_PEER_ID_BYTES");
        }
        // message is bounded only by Java's max array size; the encoded length is capped at 65535
        // (uint16) by the wire format, so we cap here too for symmetric encode/decode.
        if (message.getBytes(StandardCharsets.UTF_8).length > 0xFFFF) {
            throw new IllegalArgumentException(
                    "message UTF-8 length exceeds uint16 wire cap of 65535 bytes");
        }
    }

    /** Encode to the body bytes of a {@link FrameType#HELLO_ACK} frame. */
    public byte[] encode() {
        byte[]     peer = responderPeerId.getBytes(StandardCharsets.UTF_8);
        byte[]     msg  = message.getBytes(StandardCharsets.UTF_8);
        int        size = 1 + 2 + peer.length + 2 + msg.length;
        ByteBuffer buf  = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.put(decision.wireCode);
        buf.putShort((short) peer.length);
        buf.put(peer);
        buf.putShort((short) msg.length);
        buf.put(msg);
        return buf.array();
    }

    /** Decode from the body bytes of a {@code HELLO_ACK} frame. */
    public static HelloAckPayload decode(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            ByteBuffer buf      = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            byte       code     = buf.get();
            Decision   decision = Decision.fromWireCode(code);
            if (decision == null) {
                throw new RingProtocolException(
                        String.format("unknown HELLO_ACK decision code 0x%02X", code & 0xFF));
            }
            int peerLen = buf.getShort() & 0xFFFF;
            if (peerLen > RingProtocol.MAX_PEER_ID_BYTES) {
                throw new RingProtocolException(
                        "responderPeerId length " + peerLen + " exceeds MAX_PEER_ID_BYTES");
            }
            byte[] peer = new byte[peerLen];
            buf.get(peer);
            int    msgLen = buf.getShort() & 0xFFFF;
            byte[] msg    = new byte[msgLen];
            buf.get(msg);
            if (buf.hasRemaining()) {
                throw new RingProtocolException(
                        "trailing bytes in HELLO_ACK body: " + buf.remaining() + " unexpected bytes");
            }
            return new HelloAckPayload(
                    decision,
                    new String(peer, StandardCharsets.UTF_8),
                    new String(msg, StandardCharsets.UTF_8));
        } catch (RingProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RingProtocolException("malformed HELLO_ACK body: " + e.getMessage(), e);
        }
    }

    /** Convenience: an ACCEPT response. */
    public static HelloAckPayload accept(String responderPeerId) {
        return new HelloAckPayload(Decision.ACCEPT, responderPeerId, "");
    }

    /** Convenience: a rejection with a diagnostic. */
    public static HelloAckPayload reject(Decision decision, String responderPeerId, String message) {
        if (decision == Decision.ACCEPT) {
            throw new IllegalArgumentException("reject() called with ACCEPT — use accept() instead");
        }
        return new HelloAckPayload(decision, responderPeerId, message);
    }
}

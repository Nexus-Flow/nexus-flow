package net.nexus_flow.core.ring.wire;

import java.util.Objects;

/**
 * Wire-stable enumeration of ring frame types. Each constant carries an explicit
 * {@link #wireCode()} that is frozen for the life of the wire protocol — never reuse a retired
 * code, never depend on {@link Enum#ordinal()} (which changes when constants are reordered).
 *
 * <p>Wire codes are grouped by category for readability — but the groupings are purely
 * documentation; receivers decode by code alone:
 *
 * <ul>
 * <li>{@code 0x01-0x0F} — handshake / lifecycle ({@link #HELLO}, {@link #HELLO_ACK}, {@link
 * #PEER_LEFT}).
 * <li>{@code 0x10-0x1F} — gossip / liveness ({@link #PING}, {@link #PONG}).
 * <li>{@code 0x20-0x2F} — event fan-out ({@link #EVENT}).
 * <li>{@code 0x30-0x3F} — command routing ({@link #COMMAND_REQ}, {@link #COMMAND_RESP}).
 * <li>{@code 0x40-0x4F} — query routing ({@link #QUERY_REQ}, {@link #QUERY_RESP}).
 * <li>{@code 0x50-0x5F} — saga state replication ({@link #SAGA_STATE}).
 * <li>{@code 0x60-0x6F} — saga lease protocol ({@link #LEASE_REQ}, {@link #LEASE_GRANT}).
 * <li>{@code 0x70-0x7F} — outbox replay protocol ({@link #OUTBOX_REPLAY_REQ}, {@link
 * #OUTBOX_REPLAY_RESP}).
 * </ul>
 *
 * Codes {@code 0x80-0xFF} are reserved for future use.
 */
public enum FrameType {

    /** Initial handshake from the dialer. Body: {@link HelloPayload}. */
    HELLO((byte) 0x01),

    /**
     * Response to {@link #HELLO} from the listener. Body: {@link HelloAckPayload} — carries either
     * an acceptance or a structured rejection reason (version mismatch, incompatible
     * fingerprints, tenant scope mismatch, …).
     */
    HELLO_ACK((byte) 0x02),

    /**
     * Notification that a peer has left the ring cleanly. Body: peerId (length-prefixed UTF-8).
     * Lets recipients short-circuit failure detection for graceful shutdowns.
     */
    PEER_LEFT((byte) 0x03),

    /**
     * SWIM-style liveness probe. Body: sequence number (uint64) + sender peer id. The recipient
     * MUST reply with a matching {@link #PONG} carrying the same sequence number.
     */
    PING((byte) 0x10),

    /** Reply to a {@link #PING}; same body shape (sequence number echo + sender peer id). */
    PONG((byte) 0x11),

    /**
     * A domain event fanned out from one peer's outbox to another peer. Body carries the outbox
     * record fields needed to reconstruct an {@code EventEnvelope} at the receiver, including the
     * sender's outbox-row sequence so the receiver can advance its per-peer cursor.
     */
    EVENT((byte) 0x20),

    /**
     * Cross-pod command dispatch request. Body: correlation id + command body + ExecutionContext
     * fragment (trace/correlation/causation/messageId/tenant/deadline).
     */
    COMMAND_REQ((byte) 0x30),

    /**
     * Response to a {@link #COMMAND_REQ}. Body: correlation id + DispatchResult variant
     * discriminator (Success / Failure / PartialFailure / Accepted) + variant-specific payload.
     */
    COMMAND_RESP((byte) 0x31),

    /** Cross-pod query request. Body shape mirrors {@link #COMMAND_REQ}. */
    QUERY_REQ((byte) 0x40),

    /** Response to a {@link #QUERY_REQ}. Body shape mirrors {@link #COMMAND_RESP}. */
    QUERY_RESP((byte) 0x41),

    /**
     * Saga state replication / heartbeat frame. Body: sagaId + ownerPeerId + lease expiry +
     * SagaState payload bytes. Used both for steady-state heartbeats and for replicating state
     * changes during an ownership transfer.
     */
    SAGA_STATE((byte) 0x50),

    /**
     * Request to claim an expired saga lease. Body: sagaId + requesting peerId + requested lease
     * expiry. The current authority responds with {@link #LEASE_GRANT} or a rejection encoded as
     * a {@link #SAGA_STATE} pointing at the current owner.
     */
    LEASE_REQ((byte) 0x60),

    /**
     * Grant of a saga lease in response to {@link #LEASE_REQ}. Body: sagaId + grantedToPeerId +
     * granted lease expiry.
     */
    LEASE_GRANT((byte) 0x61),

    /**
     * Outbox replay request — peer-to-peer "send me everything in your outbox since cursor X for
     * tenants T". Body: requestor peerId + last-known outboxId cursor + tenant filter.
     */
    OUTBOX_REPLAY_REQ((byte) 0x70),

    /**
     * Outbox replay response containing one batch of historic events. Body: batch frame count +
     * repeated {@code outboxId + payload}. Multiple {@link #OUTBOX_REPLAY_RESP} frames may follow
     * a single {@link #OUTBOX_REPLAY_REQ}.
     */
    OUTBOX_REPLAY_RESP((byte) 0x71);

    private final byte wireCode;

    FrameType(byte wireCode) {
        this.wireCode = wireCode;
    }

    /**
     * Returns the immutable byte that represents this {@code FrameType} on the wire. Stable
     * across enum reordering — never relies on {@link #ordinal()}.
     *
     * @return the wire code byte
     */
    public byte wireCode() {
        return wireCode;
    }

    /**
     * Resolves a {@code FrameType} from its wire code, or {@code null} if the code is not
     * recognised. Receivers MUST treat a {@code null} result as a protocol violation and close the
     * connection (do NOT silently ignore unknown frame types — that hides protocol drift between
     * peers running different code).
     *
     * @param wireCode the byte read from the {@code type} field of the frame header
     * @return the resolved {@code FrameType}, or {@code null} if unknown
     */
    public static FrameType fromWireCode(byte wireCode) {
        for (FrameType t : VALUES) {
            if (t.wireCode == wireCode) {
                return t;
            }
        }
        return null;
    }

    /**
     * Resolves a {@code FrameType} from its wire code, throwing {@link RingProtocolException} when
     * the code is unrecognised. Preferred over {@link #fromWireCode(byte)} on read paths where a
     * {@code null} would just be turned into an exception two lines later.
     *
     * @param wireCode the byte read from the {@code type} field of the frame header
     * @return the resolved {@code FrameType}; never {@code null}
     * @throws RingProtocolException if {@code wireCode} does not correspond to a known frame type
     */
    public static FrameType requireFromWireCode(byte wireCode) {
        FrameType t = fromWireCode(wireCode);
        if (t == null) {
            throw new RingProtocolException(
                    String.format("unknown frame type wire code 0x%02X", wireCode & 0xFF));
        }
        return t;
    }

    /**
     * Cached array of all values to avoid per-lookup {@link #values()} allocation in
     * {@link #fromWireCode(byte)} (called on every received frame in a tight loop).
     */
    private static final FrameType[] VALUES = values();

    static {
        // Wire-code uniqueness invariant — verified at class init so a future copy/paste error that
        // accidentally reuses a code fails fast (loud ExceptionInInitializerError) instead of
        // silently corrupting the wire format at the first frame.
        java.util.BitSet seen = new java.util.BitSet(256);
        for (FrameType t : VALUES) {
            int code = t.wireCode & 0xFF;
            if (seen.get(code)) {
                throw new IllegalStateException(
                        "FrameType wire code 0x"
                                + Integer.toHexString(code)
                                + " is reused — wire codes MUST be unique across all enum constants");
            }
            seen.set(code);
        }
        Objects.requireNonNull(VALUES, "values");
    }
}

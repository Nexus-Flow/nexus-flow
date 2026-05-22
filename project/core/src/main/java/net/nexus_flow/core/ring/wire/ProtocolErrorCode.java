package net.nexus_flow.core.ring.wire;

/**
 * Wire-stable error code carried in dispatch responses and other reply envelopes. The set is
 * deliberately small and category-shaped so callers can branch on policy ("retry" /
 * "no-retry") without parsing free-form messages. Adding a new code is a wire-version change
 * just like adding a new {@link FrameType}; never reuse a retired byte value.
 *
 * <h2>Why a code, not a free-form message</h2>
 *
 * The previous design echoed {@code exception.getMessage()} from the local handler back to the
 * remote peer. That leaks internal class names, file paths, stack-trace fragments, and bean
 * names — useful for the originator's logs but a confidentiality leak when the peer is only
 * partially trusted ({@link net.nexus_flow.core.ring} threat model §10). A discrete code keeps
 * the wire envelope sanitised; the originator's local logs keep the full diagnostic.
 *
 * <h2>Wire encoding</h2>
 *
 * 1 unsigned byte. Unknown codes are folded to {@link #UNKNOWN} at decode time so a forward-
 * version peer's new code does not crash an older receiver; the receiver still surfaces the
 * outcome as an error and the policy layer treats unknown codes conservatively.
 */
public enum ProtocolErrorCode {

    /** Reserved sentinel — never sent on the wire; carried only on a {@code SUCCESS} envelope. */
    OK((byte) 0x00),

    /** The peer no longer handles the requested type (routing decision was stale). */
    NOT_FOUND((byte) 0x01),

    /** The peer received the request but the local handler exceeded its deadline. */
    DEADLINE_EXCEEDED((byte) 0x02),

    /** The peer's outbound queue rejected the response — sender should treat as transient. */
    BACKPRESSURE((byte) 0x03),

    /**
     * The peer's authorization layer refused the dispatch. Originator MUST NOT retry without
     * operator action; the refusal is an authentication/tenant/role decision.
     */
    FORBIDDEN((byte) 0x04),

    /** Request envelope was decoded but its fields violate a contract (e.g. wrong codec id). */
    INVALID_REQUEST((byte) 0x05),

    /**
     * Local handler returned a domain failure ({@link
     * net.nexus_flow.core.runtime.result.DispatchResult.Failure}).
     */
    DOMAIN_FAILURE((byte) 0x06),

    /**
     * Local handler returned a partial failure ({@link
     * net.nexus_flow.core.runtime.result.DispatchResult.PartialFailure}).
     */
    PARTIAL_FAILURE((byte) 0x07),

    /** Local handler threw a non-domain exception; the originator should treat as transient. */
    INTERNAL((byte) 0x08),

    /**
     * Peer is shutting down / draining. The originator should re-route to another peer if
     * possible.
     */
    UNAVAILABLE((byte) 0x09),

    /**
     * Forward-compatibility sentinel — decode returned an unrecognised code. Receivers treat
     * UNKNOWN as a non-retryable failure: the originator does not know whether the peer's
     * state actually changed.
     */
    UNKNOWN((byte) 0xFF);

    private final byte wireCode;

    ProtocolErrorCode(byte wireCode) {
        this.wireCode = wireCode;
    }

    /** @return the immutable wire byte. */
    public byte wireCode() {
        return wireCode;
    }

    /**
     * Decode {@code code} to a known {@code ProtocolErrorCode}. Returns {@link #UNKNOWN} when
     * the byte is not recognised — never throws — so a future-version peer's new code does not
     * fail the response decoder.
     */
    public static ProtocolErrorCode fromWireCode(byte code) {
        for (ProtocolErrorCode v : VALUES) {
            if (v.wireCode == code) {
                return v;
            }
        }
        return UNKNOWN;
    }

    /** @return whether retrying after backoff has a meaningful chance of success. */
    public boolean isRetryable() {
        return switch (this) {
            case BACKPRESSURE, INTERNAL, UNAVAILABLE                                                                    -> true;
            case OK, NOT_FOUND, DEADLINE_EXCEEDED, FORBIDDEN, INVALID_REQUEST,
                    DOMAIN_FAILURE, PARTIAL_FAILURE, UNKNOWN                                                            ->
                 false;
        };
    }

    private static final ProtocolErrorCode[] VALUES = values();
}

package net.nexus_flow.core.ring.transport;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.nexus_flow.core.ring.wire.RingProtocol;

/**
 * Newtype wrapping a peer's stable identifier. Distinct type (not raw {@code String}) so the
 * compiler catches accidental swaps between peer ids, tenant ids, message ids, and other
 * string-typed handles that share the same lexical shape.
 *
 * <p>The validation rules match {@link net.nexus_flow.core.ring.wire.HelloPayload}'s
 * compact-constructor checks — non-blank, UTF-8 encoded length ≤ {@link
 * RingProtocol#MAX_PEER_ID_BYTES}. Failing fast at construction means a misconfigured
 * deployment manifests at startup, not on first connection attempt.
 *
 * @param value the underlying peer id string; never {@code null}, never blank, ≤ {@link
 *              RingProtocol#MAX_PEER_ID_BYTES} bytes when UTF-8 encoded
 */
public record PeerId(String value) {

    /** Compact constructor with full validation. */
    public PeerId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("peer id must not be blank");
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > RingProtocol.MAX_PEER_ID_BYTES) {
            throw new IllegalArgumentException(
                    "peer id UTF-8 length "
                            + bytes
                            + " exceeds MAX_PEER_ID_BYTES = "
                            + RingProtocol.MAX_PEER_ID_BYTES);
        }
    }

    /** Convenience factory matching the validation in the compact constructor. */
    public static PeerId of(String value) {
        return new PeerId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

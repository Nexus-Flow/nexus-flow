package net.nexus_flow.core.ring.transport;

import java.io.Serial;

/**
 * Failure to dial a peer. The {@link Classification} discriminator lets callers (membership
 * layer, retry policy) take programmatic action — DNS failure → no retry on the same address;
 * connection refused → maybe the peer is starting → retry with backoff; TLS handshake failure
 * → the cert is wrong → no point retrying without operator intervention.
 *
 * <p>Per skill §7 and §16: connect failures MUST be classified, not lumped together.
 */
public final class RingDialException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Discriminator for the four major dial-failure flavours. Stable across versions. */
    public enum Classification {
        /** {@link java.net.UnknownHostException} — DNS resolution failed. */
        UNKNOWN_HOST,
        /** TCP connect refused — the peer is not listening on that port. */
        CONNECTION_REFUSED,
        /** Connect or TLS handshake timed out. */
        TIMEOUT,
        /** TLS handshake failed (bad cert, untrusted issuer, protocol mismatch). */
        TLS_HANDSHAKE_FAILED,
        /** Any other transport-level failure. */
        TRANSPORT
    }

    private final transient Classification classification;
    private final transient PeerAddress    address;

    public RingDialException(
            Classification classification, PeerAddress address, String message, Throwable cause) {
        // Stack-traceless — the dial site is always the dialer's connect/handshake attempt
        // (predictable location). Actionable diagnostics live in classification + address +
        // cause. Suppression chain stays active. Saves ~200 ns per dial failure throw.
        super(
              "dial to " + address + " failed (" + classification + "): " + message,
              cause,
              /* enableSuppression= */ true,
              /* writableStackTrace= */ false);
        this.classification = classification;
        this.address        = address;
    }

    public Classification classification() {
        return classification;
    }

    public PeerAddress address() {
        return address;
    }
}

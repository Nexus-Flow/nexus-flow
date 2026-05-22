package net.nexus_flow.core.ring.wire;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A 32-byte SHA-256 fingerprint identifying the wire shape of a domain type (event, command, or
 * query payload). Carried in {@link HelloPayload} so two peers can detect schema drift at
 * connection time — peers with different fingerprints for the same {@code typeName} agree to
 * route only to peers whose fingerprint matches (or reject the connection entirely, per
 * deployment policy).
 *
 * <p><strong>What goes into the hash</strong> is implementation-defined by the framework — for
 * {@code AbstractDomainEvent} subclasses it is typically the FQN + the declared serializable
 * fields in declaration order + their declared types. The fingerprint MUST be deterministic
 * across JVM instances of the same compiled type; reflecting field declaration order
 * (NOT iteration order of any {@code Map}) is the load-bearing requirement.
 *
 * <p>The wrapper validates length and provides convenient {@link #toHex()} for diagnostics.
 *
 * @param bytes the 32-byte SHA-256 digest; defensively copied
 */
public record TypeFingerprint(byte[] bytes) {

    /** Compact constructor: validates length and defensively copies. */
    public TypeFingerprint {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != RingProtocol.FINGERPRINT_BYTES) {
            throw new IllegalArgumentException(
                    "fingerprint must be exactly "
                            + RingProtocol.FINGERPRINT_BYTES
                            + " bytes (SHA-256), got "
                            + bytes.length);
        }
        bytes = bytes.clone();
    }

    /** Returns a defensive copy of the underlying bytes. */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Computes a fingerprint from arbitrary bytes by running them through SHA-256. Convenient for
     * callers that have already canonicalised the type description; not the framework's standard
     * fingerprint algorithm (which lives in the {@code introspection} package and inspects the
     * type's structure).
     *
     * @param input bytes to digest; must not be {@code null}
     * @return the SHA-256 fingerprint of {@code input}
     */
    public static TypeFingerprint sha256Of(byte[] input) {
        Objects.requireNonNull(input, "input");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return new TypeFingerprint(md.digest(input));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by every conformant JDK; surfacing this means the JVM is broken.
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    /** Returns the hex representation of the fingerprint — useful for logs and diagnostics. */
    public String toHex() {
        return HexFormat.of().formatHex(bytes);
    }

    /** Structural equality on the digest bytes. */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof TypeFingerprint other))
            return false;
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "TypeFingerprint{" + toHex() + '}';
    }
}

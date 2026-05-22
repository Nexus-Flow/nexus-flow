package net.nexus_flow.core.ring.wire;

/**
 * Wire-protocol constants for the Nexus Flow ring. Frozen values once published — bumping any
 * of them is a protocol-version change (increment {@link #VERSION}) so peers reject mismatched
 * frames at handshake time instead of silently corrupting state.
 */
public final class RingProtocol {

    private RingProtocol() {
    }

    /**
     * 4-byte magic identifier {@code N X F R} (0x4E 0x58 0x46 0x52) sent at the start of every
     * ring frame. Lets a receiver classify "wrong protocol" (peer trying to speak HTTP / Kafka /
     * garbage) immediately rather than wedging on partial reads of a bogus length field.
     */
    public static final byte[] MAGIC = {(byte) 'N', (byte) 'X', (byte) 'F', (byte) 'R'};

    /** Number of bytes in {@link #MAGIC} — exposed for decoders/encoders that hardcode offsets. */
    public static final int MAGIC_BYTES = 4;

    /**
     * Current wire-protocol version. Bumping this is a wire-incompatible change — every peer in
     * the cluster must either roll first, or the handshake rejects the connection. Use the
     * {@code eventTypeFingerprints} / {@code commandTypeFingerprints} / {@code queryTypeFingerprints}
     * fields of {@link HelloPayload} to communicate schema changes WITHIN a wire version.
     */
    public static final byte VERSION = 1;

    /**
     * Total bytes of the fixed header: {@link #MAGIC_BYTES} + 1 (version) + 1 (type) + 2 (flags) +
     * 4 (body length) = 12. Decoders need the full header buffered before they can resolve the
     * body length.
     */
    public static final int HEADER_BYTES = MAGIC_BYTES + 1 + 1 + 2 + 4;

    /**
     * Default cap on the body length field — 16 MiB. Receivers MUST enforce a maximum (this value
     * or stricter) and close the connection if a peer announces a larger frame. Without the cap, a
     * peer that wrote {@code Integer.MAX_VALUE} as the length forces the receiver to either
     * allocate 2 GiB or to wedge waiting for bytes that will never arrive — the classic
     * length-prefixed-protocol DoS.
     */
    public static final int DEFAULT_MAX_BODY_BYTES = 16 * 1024 * 1024;

    /**
     * Max peer-id length in bytes (UTF-8). Peer ids are human-readable handles; 128 bytes is
     * generous enough for {@code pod-name.namespace.cluster.region} style identifiers and small
     * enough to fit comfortably inside a single TCP segment.
     */
    public static final int MAX_PEER_ID_BYTES = 128;

    /**
     * Max length of a fully-qualified type name (event / command / query class name) in bytes.
     * Standard JVM class-name limit + room for nested generics encoded as strings.
     */
    public static final int MAX_TYPE_NAME_BYTES = 256;

    /**
     * Fixed length of a SHA-256 type fingerprint in bytes. Hard-coded rather than configurable —
     * different hash functions would render fingerprints incomparable.
     */
    public static final int FINGERPRINT_BYTES = 32;

    /**
     * Defensive cap on the number of type fingerprints carried in a single {@link HelloPayload}.
     * 10k is far above any realistic per-pod handler count and protects against a peer that floods
     * the handshake with bogus entries to exhaust the receiver's memory.
     */
    public static final int MAX_FINGERPRINTS_PER_HELLO = 10_000;

    /**
     * Defensive cap on the number of tenant ids carried in a {@link HelloPayload}'s tenant set.
     * Multi-tenant deployments rarely exceed hundreds of tenants per pod; 4096 leaves ample
     * headroom while still bounding the allocation.
     */
    public static final int MAX_TENANTS_PER_HELLO = 4096;

    /** Max tenant-id length in bytes (UTF-8). */
    public static final int MAX_TENANT_ID_BYTES = 128;
}

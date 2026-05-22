package net.nexus_flow.core.ring.wire;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Handshake payload sent by the dialer immediately after the TCP+TLS connection is established
 * (frame type {@link FrameType#HELLO}). The listener replies with {@link HelloAckPayload} —
 * either acceptance or a structured rejection.
 *
 * <h2>What's in here</h2>
 *
 * <ul>
 * <li>{@code echoedProtocolVersion} — duplicate of the frame-header version field. The
 * receiver checks both and rejects mismatches; the duplication is intentional defensive
 * depth because a buggy peer could write inconsistent header/body version values.
 * <li>{@code peerId} — stable, human-readable handle the peer announces (typically
 * {@code pod-name.namespace}). Used by all subsequent ring operations (gossip, routing,
 * lease ownership). UTF-8, max {@link RingProtocol#MAX_PEER_ID_BYTES}.
 * <li>{@code eventTypes}, {@code commandTypes}, {@code queryTypes} — maps of type FQN → 32-byte
 * fingerprint. Lets receivers detect schema drift per-type at connection time. Empty
 * maps mean "I handle no types of this category" (e.g. an event-only pod has empty
 * command and query maps).
 * <li>{@code tenants} — set of tenant ids the peer serves; the wildcard set
 * {@link #ALL_TENANTS_SENTINEL} (singleton with {@code "*"}) means "multi-tenant; route
 * everything to me".
 * </ul>
 *
 * <h2>Wire format (body of a HELLO frame)</h2>
 *
 * <pre>
 * ubyte echoedProtocolVersion
 * string peerId (uint16 length-prefixed UTF-8)
 * uint16 eventTypeCount
 * repeated {
 * string typeName (uint16 length-prefixed UTF-8)
 * byte[32] fingerprint
 * } * eventTypeCount
 * uint16 commandTypeCount
 * repeated { string typeName, byte[32] fingerprint } * commandTypeCount
 * uint16 queryTypeCount
 * repeated { string typeName, byte[32] fingerprint } * queryTypeCount
 * uint16 tenantCount
 * repeated string tenantId * tenantCount
 * </pre>
 *
 * Every length / count field is bounded by the defensive caps in {@link RingProtocol} to make
 * the decoder DoS-resistant.
 *
 * @param echoedProtocolVersion the wire version the dialer believes it is speaking (defensive
 *                              duplicate of the frame-header version)
 * @param peerId                the dialer's stable handle; never {@code null} or blank, max
 *                              {@link RingProtocol#MAX_PEER_ID_BYTES} UTF-8 bytes
 * @param eventTypes            immutable map from event type FQN to fingerprint
 * @param commandTypes          immutable map from command type FQN to fingerprint
 * @param queryTypes            immutable map from query type FQN to fingerprint
 * @param tenants               immutable set of tenant ids this peer serves; {@link #ALL_TENANTS_SENTINEL} for
 *                              wildcard multi-tenant
 */
public record HelloPayload(
                           byte echoedProtocolVersion,
                           String peerId,
                           Map<String, TypeFingerprint> eventTypes,
                           Map<String, TypeFingerprint> commandTypes,
                           Map<String, TypeFingerprint> queryTypes,
                           Set<String> tenants) {

    /**
     * Singleton tenant set carrying the {@code "*"} wildcard. A peer announcing this set
     * acknowledges that it serves every tenant the ring routes to it; receivers SHOULD treat the
     * peer as a candidate target for any tenant-scoped routing decision.
     */
    public static final Set<String> ALL_TENANTS_SENTINEL = Set.of("*");

    /** Compact constructor: validation and defensive copies. */
    public HelloPayload {
        Objects.requireNonNull(peerId, "peerId");
        if (peerId.isBlank()) {
            throw new IllegalArgumentException("peerId must not be blank");
        }
        byte[] peerIdBytes = peerId.getBytes(StandardCharsets.UTF_8);
        if (peerIdBytes.length > RingProtocol.MAX_PEER_ID_BYTES) {
            throw new IllegalArgumentException(
                    "peerId UTF-8 length "
                            + peerIdBytes.length
                            + " exceeds MAX_PEER_ID_BYTES = "
                            + RingProtocol.MAX_PEER_ID_BYTES);
        }
        Objects.requireNonNull(eventTypes, "eventTypes");
        Objects.requireNonNull(commandTypes, "commandTypes");
        Objects.requireNonNull(queryTypes, "queryTypes");
        Objects.requireNonNull(tenants, "tenants");
        int totalFingerprints = eventTypes.size() + commandTypes.size() + queryTypes.size();
        if (totalFingerprints > RingProtocol.MAX_FINGERPRINTS_PER_HELLO) {
            throw new IllegalArgumentException(
                    "total fingerprints "
                            + totalFingerprints
                            + " exceeds MAX_FINGERPRINTS_PER_HELLO = "
                            + RingProtocol.MAX_FINGERPRINTS_PER_HELLO);
        }
        if (tenants.size() > RingProtocol.MAX_TENANTS_PER_HELLO) {
            throw new IllegalArgumentException(
                    "tenant count "
                            + tenants.size()
                            + " exceeds MAX_TENANTS_PER_HELLO = "
                            + RingProtocol.MAX_TENANTS_PER_HELLO);
        }
        for (String tenant : tenants) {
            Objects.requireNonNull(tenant, "tenant id");
            if (tenant.getBytes(StandardCharsets.UTF_8).length > RingProtocol.MAX_TENANT_ID_BYTES) {
                throw new IllegalArgumentException(
                        "tenant id UTF-8 length exceeds MAX_TENANT_ID_BYTES = "
                                + RingProtocol.MAX_TENANT_ID_BYTES);
            }
        }
        eventTypes   = Map.copyOf(eventTypes);
        commandTypes = Map.copyOf(commandTypes);
        queryTypes   = Map.copyOf(queryTypes);
        // Store tenants in deterministic (sorted) iteration order so encode() can iterate
        // without re-allocating a TreeSet per call. LinkedHashSet preserves the insertion
        // order from the TreeSet (sorted); the unmodifiable wrapper enforces immutability.
        tenants = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(tenants)));
    }

    /**
     * Serialise this payload into the body bytes of a {@link FrameType#HELLO} frame.
     *
     * @return the wire-encoded body bytes; never {@code null}
     */
    public byte[] encode() {
        // Two-pass: compute size first to allocate once, then write. Avoids growing-buffer
        // re-allocation on the encode hot path even though hello frames are rare.
        int        size = computeEncodedSize();
        ByteBuffer buf  = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.put(echoedProtocolVersion);
        writeString(buf, peerId);
        writeFingerprintMap(buf, eventTypes);
        writeFingerprintMap(buf, commandTypes);
        writeFingerprintMap(buf, queryTypes);
        writeUnsignedShort(buf, tenants.size());
        // Tenants are already stored in sorted iteration order (see compact constructor),
        // so encode() does not need to re-allocate a TreeSet per call.
        for (String tenant : tenants) {
            writeString(buf, tenant);
        }
        return buf.array();
    }

    /**
     * Deserialise a {@code HELLO} frame body into a {@code HelloPayload}.
     *
     * @param body the frame body bytes; must not be {@code null}
     * @return the decoded payload
     * @throws RingProtocolException if the body does not parse, or violates any defensive cap
     */
    public static HelloPayload decode(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            ByteBuffer                   buf         = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            byte                         version     = buf.get();
            String                       peerId      = readString(buf, RingProtocol.MAX_PEER_ID_BYTES);
            Map<String, TypeFingerprint> events      = readFingerprintMap(buf);
            Map<String, TypeFingerprint> commands    = readFingerprintMap(buf);
            Map<String, TypeFingerprint> queries     = readFingerprintMap(buf);
            int                          tenantCount = readUnsignedShort(buf);
            if (tenantCount > RingProtocol.MAX_TENANTS_PER_HELLO) {
                throw new RingProtocolException(
                        "tenant count " + tenantCount + " exceeds MAX_TENANTS_PER_HELLO");
            }
            Set<String> tenants = new TreeSet<>();
            for (int i = 0; i < tenantCount; i++) {
                tenants.add(readString(buf, RingProtocol.MAX_TENANT_ID_BYTES));
            }
            if (buf.hasRemaining()) {
                throw new RingProtocolException(
                        "trailing bytes in HELLO body: " + buf.remaining() + " unexpected bytes");
            }
            return new HelloPayload(version, peerId, events, commands, queries, tenants);
        } catch (RingProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RingProtocolException("malformed HELLO body: " + e.getMessage(), e);
        }
    }

    private int computeEncodedSize() {
        int size = 1; // echoedProtocolVersion
        size += 2 + peerId.getBytes(StandardCharsets.UTF_8).length;
        size += fingerprintMapEncodedSize(eventTypes);
        size += fingerprintMapEncodedSize(commandTypes);
        size += fingerprintMapEncodedSize(queryTypes);
        size += 2; // tenantCount
        for (String t : tenants) {
            size += 2 + t.getBytes(StandardCharsets.UTF_8).length;
        }
        return size;
    }

    private static int fingerprintMapEncodedSize(Map<String, TypeFingerprint> map) {
        int size = 2; // count
        for (String key : map.keySet()) {
            size += 2 + key.getBytes(StandardCharsets.UTF_8).length + RingProtocol.FINGERPRINT_BYTES;
        }
        return size;
    }

    private static void writeFingerprintMap(ByteBuffer buf, Map<String, TypeFingerprint> map) {
        writeUnsignedShort(buf, map.size());
        // Deterministic order — sort by key so two encodes of the same logical payload are
        // bytewise-identical (matters for any future signing / replay-attack-protection layer).
        Map<String, TypeFingerprint> sorted = new java.util.TreeMap<>(map);
        for (Map.Entry<String, TypeFingerprint> e : sorted.entrySet()) {
            writeString(buf, e.getKey());
            // bytesUnsafe() avoids an unnecessary 32-byte clone per fingerprint — we only
            // copy the bytes INTO the buffer, never store the reference.
            buf.put(e.getValue().bytesUnsafe());
        }
    }

    private static Map<String, TypeFingerprint> readFingerprintMap(ByteBuffer buf) {
        int count = readUnsignedShort(buf);
        if (count > RingProtocol.MAX_FINGERPRINTS_PER_HELLO) {
            throw new RingProtocolException(
                    "fingerprint count " + count + " exceeds MAX_FINGERPRINTS_PER_HELLO");
        }
        // HashMap.newHashMap(count) avoids the previous count*2 over-allocation while still
        // sizing for no-rehash insertion of `count` entries.
        Map<String, TypeFingerprint> result = LinkedHashMap.newLinkedHashMap(count);
        for (int i = 0; i < count; i++) {
            String typeName = readString(buf, RingProtocol.MAX_TYPE_NAME_BYTES);
            byte[] fp       = new byte[RingProtocol.FINGERPRINT_BYTES];
            buf.get(fp);
            TypeFingerprint prev = result.put(typeName, new TypeFingerprint(fp));
            if (prev != null) {
                throw new RingProtocolException("duplicate type name in HELLO body: " + typeName);
            }
        }
        return result;
    }

    private static void writeUnsignedShort(ByteBuffer buf, int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("uint16 out of range: " + value);
        }
        buf.putShort((short) value);
    }

    private static int readUnsignedShort(ByteBuffer buf) {
        return buf.getShort() & 0xFFFF;
    }

    private static void writeString(ByteBuffer buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeUnsignedShort(buf, bytes.length);
        buf.put(bytes);
    }

    private static String readString(ByteBuffer buf, int maxBytes) {
        int len = readUnsignedShort(buf);
        if (len > maxBytes) {
            throw new RingProtocolException(
                    "string length " + len + " exceeds cap " + maxBytes);
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Convenience factory: an empty {@code HelloPayload} carrying the current wire version, the
     * given peer id, no type fingerprints, and the wildcard tenant set. Useful for tests and for
     * peers that bootstrap before any handler has been registered.
     *
     * @param peerId the peer id; must not be {@code null} or blank
     * @return an empty-fingerprints, wildcard-tenants payload
     */
    public static HelloPayload empty(String peerId) {
        return new HelloPayload(
                RingProtocol.VERSION,
                peerId,
                Map.of(),
                Map.of(),
                Map.of(),
                ALL_TENANTS_SENTINEL);
    }

    /** Returns the list of every type name carried in this hello, across all three categories. */
    public List<String> allTypeNames() {
        List<String> all = new java.util.ArrayList<>(
                eventTypes.size() + commandTypes.size() + queryTypes.size());
        all.addAll(eventTypes.keySet());
        all.addAll(commandTypes.keySet());
        all.addAll(queryTypes.keySet());
        return List.copyOf(all);
    }
}

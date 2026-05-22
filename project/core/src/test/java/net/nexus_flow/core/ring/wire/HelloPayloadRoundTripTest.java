package net.nexus_flow.core.ring.wire;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link HelloPayload} encode/decode round-trip and the defensive caps. The handshake
 * payload is the most operator-visible part of the wire protocol — drift in this serializer
 * silently fragments the cluster.
 */
class HelloPayloadRoundTripTest {

    private static TypeFingerprint fp(int seed) {
        byte[] bytes = new byte[RingProtocol.FINGERPRINT_BYTES];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) ((seed * 31 + i) & 0xFF);
        return new TypeFingerprint(bytes);
    }

    @Test
    void emptyPayload_roundTrips() {
        HelloPayload original = HelloPayload.empty("pod-alpha");
        HelloPayload decoded  = HelloPayload.decode(original.encode());
        assertEquals(original, decoded);
    }

    @Test
    void populatedPayload_roundTrips_preservingEveryField() {
        Map<String, TypeFingerprint> events = new LinkedHashMap<>();
        events.put("com.acme.OrderPlaced", fp(1));
        events.put("com.acme.OrderShipped", fp(2));
        Map<String, TypeFingerprint> commands = Map.of("com.acme.PlaceOrder", fp(3));
        Map<String, TypeFingerprint> queries  = Map.of("com.acme.FindOrder", fp(4));
        Set<String>                  tenants  = Set.of("acme", "umbra", "globex");

        HelloPayload original =
                new HelloPayload(RingProtocol.VERSION, "pod-acme-7", events, commands, queries, tenants);
        HelloPayload decoded  = HelloPayload.decode(original.encode());
        assertEquals(original, decoded);
        assertEquals(events.keySet(), decoded.eventTypes().keySet());
        assertEquals(commands, decoded.commandTypes());
        assertEquals(queries, decoded.queryTypes());
        assertEquals(tenants, decoded.tenants());
    }

    @Test
    void encodeIsDeterministic_sameLogicalPayloadProducesIdenticalBytes() {
        // Different insertion orders in the input maps must NOT change the encoded bytes — operators
        // who hash the hello body for audit / replay-detection must get a stable hash regardless of
        // the host's HashMap iteration order.
        Map<String, TypeFingerprint> mapA = new LinkedHashMap<>();
        mapA.put("z.Z", fp(1));
        mapA.put("a.A", fp(2));
        Map<String, TypeFingerprint> mapB = new LinkedHashMap<>();
        mapB.put("a.A", fp(2));
        mapB.put("z.Z", fp(1));
        HelloPayload pA = new HelloPayload(RingProtocol.VERSION, "p", mapA, Map.of(), Map.of(), Set.of("t"));
        HelloPayload pB = new HelloPayload(RingProtocol.VERSION, "p", mapB, Map.of(), Map.of(), Set.of("t"));
        assertArrayEquals(pA.encode(), pB.encode());
    }

    @Test
    void emptyPeerId_isRejectedAtConstruction() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new HelloPayload(RingProtocol.VERSION, "", Map.of(), Map.of(), Map.of(), Set.of("t")));
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new HelloPayload(RingProtocol.VERSION, "  ", Map.of(), Map.of(), Map.of(), Set.of("t")));
    }

    @Test
    void peerIdLongerThanCap_isRejectedAtConstruction() {
        String tooLong = "x".repeat(RingProtocol.MAX_PEER_ID_BYTES + 1);
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new HelloPayload(
                             RingProtocol.VERSION, tooLong, Map.of(), Map.of(), Map.of(), Set.of("t")));
    }

    @Test
    void totalFingerprintCountAboveCap_isRejectedAtConstruction() {
        Map<String, TypeFingerprint> huge = new LinkedHashMap<>();
        for (int i = 0; i <= RingProtocol.MAX_FINGERPRINTS_PER_HELLO; i++) {
            huge.put("t" + i, fp(i));
        }
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new HelloPayload(RingProtocol.VERSION, "p", huge, Map.of(), Map.of(), Set.of("t")));
    }

    @Test
    void duplicateTypeNameInDecodedBody_throwsRingProtocolException() {
        // Craft a body with two entries for the same type name.
        byte[] echo      = new byte[]{RingProtocol.VERSION};
        String peer      = "p";
        byte[] peerBytes = peer.getBytes(StandardCharsets.UTF_8);
        String dupName   = "com.acme.X";
        byte[] dupBytes  = dupName.getBytes(StandardCharsets.UTF_8);
        byte[] fpBytes   = fp(1).bytes();

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1024).order(java.nio.ByteOrder.BIG_ENDIAN);
        buf.put(echo);
        buf.putShort((short) peerBytes.length);
        buf.put(peerBytes);
        // event count = 2, both entries with the same name
        buf.putShort((short) 2);
        for (int i = 0; i < 2; i++) {
            buf.putShort((short) dupBytes.length);
            buf.put(dupBytes);
            buf.put(fpBytes);
        }
        // command + query + tenant counts = 0
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        byte[] body = new byte[buf.position()];
        buf.flip();
        buf.get(body);

        RingProtocolException ex = assertThrows(RingProtocolException.class, () -> HelloPayload.decode(body));
        assertTrue(ex.getMessage().contains("duplicate type name"), ex.getMessage());
    }

    @Test
    void trailingBytesInDecodedBody_throwsRingProtocolException() {
        byte[] valid        = HelloPayload.empty("p").encode();
        byte[] withTrailing = new byte[valid.length + 4];
        System.arraycopy(valid, 0, withTrailing, 0, valid.length);
        // last 4 bytes are zeros — should be flagged as trailing
        RingProtocolException ex =
                assertThrows(RingProtocolException.class, () -> HelloPayload.decode(withTrailing));
        assertTrue(ex.getMessage().contains("trailing bytes"), ex.getMessage());
    }
}

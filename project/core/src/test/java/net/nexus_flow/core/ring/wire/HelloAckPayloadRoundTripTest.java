package net.nexus_flow.core.ring.wire;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link HelloAckPayload} round-trip and the {@link HelloAckPayload.Decision} wire-code
 * stability. Decision codes are part of the wire protocol — drift here makes dialers
 * misclassify a rejection (treat ACCEPT as VERSION_MISMATCH or vice versa) which would
 * produce silent connection-drop loops.
 */
class HelloAckPayloadRoundTripTest {

    @Test
    void acceptResponse_roundTrips() {
        HelloAckPayload original = HelloAckPayload.accept("pod-listener-3");
        HelloAckPayload decoded  = HelloAckPayload.decode(original.encode());
        assertEquals(HelloAckPayload.Decision.ACCEPT, decoded.decision());
        assertEquals("pod-listener-3", decoded.responderPeerId());
        assertEquals("", decoded.message());
    }

    @Test
    void rejectResponse_preservesDecisionAndMessage() {
        HelloAckPayload original =
                HelloAckPayload.reject(
                                       HelloAckPayload.Decision.FINGERPRINT_MISMATCH,
                                       "pod-listener",
                                       "event com.acme.OrderPlaced fingerprint differs");
        HelloAckPayload decoded  = HelloAckPayload.decode(original.encode());
        assertEquals(HelloAckPayload.Decision.FINGERPRINT_MISMATCH, decoded.decision());
        assertEquals("pod-listener", decoded.responderPeerId());
        assertEquals("event com.acme.OrderPlaced fingerprint differs", decoded.message());
    }

    @Test
    void everyDecisionRoundTrips_byWireCode() {
        for (HelloAckPayload.Decision d : HelloAckPayload.Decision.values()) {
            assertSame(d, HelloAckPayload.Decision.fromWireCode(d.wireCode()));
        }
    }

    @Test
    void decisionWireCodes_areFrozen() {
        assertEquals((byte) 0x00, HelloAckPayload.Decision.ACCEPT.wireCode());
        assertEquals((byte) 0x10, HelloAckPayload.Decision.VERSION_MISMATCH.wireCode());
        assertEquals((byte) 0x20, HelloAckPayload.Decision.FINGERPRINT_MISMATCH.wireCode());
        assertEquals((byte) 0x30, HelloAckPayload.Decision.TENANT_SCOPE_MISMATCH.wireCode());
        assertEquals((byte) 0x40, HelloAckPayload.Decision.PEER_ID_CONFLICT.wireCode());
        assertEquals((byte) 0x50, HelloAckPayload.Decision.CAPACITY_EXCEEDED.wireCode());
        assertEquals((byte) 0x60, HelloAckPayload.Decision.TRANSIENT_REJECT.wireCode());
    }

    @Test
    void unknownDecisionCode_decodeThrows() {
        byte[]                body = new byte[]{(byte) 0xFE, 0, 0, 0, 0};
        RingProtocolException ex   =
                assertThrows(RingProtocolException.class, () -> HelloAckPayload.decode(body));
        assertTrue(ex.getMessage().contains("0xFE"), ex.getMessage());
    }

    @Test
    void rejectCalledWithAccept_throwsImmediately() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> HelloAckPayload.reject(HelloAckPayload.Decision.ACCEPT, "p", "msg"));
    }
}

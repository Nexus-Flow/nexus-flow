package net.nexus_flow.core.ring.wire;

import static org.junit.jupiter.api.Assertions.*;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link FrameType} wire-code stability: every constant has a unique byte code, codes are
 * NOT the enum {@code ordinal()} (which would change on reordering), and the
 * {@code fromWireCode}/{@code requireFromWireCode} resolvers do what they claim. The class-init
 * uniqueness check already throws at JVM start if a code is duplicated; this suite is the
 * structural pin so future contributors who add a constant cannot accidentally reuse a code.
 */
class FrameTypeWireCodeTest {

    @Test
    void everyWireCodeIsUnique() {
        BitSet seen = new BitSet(256);
        for (FrameType t : FrameType.values()) {
            int code = t.wireCode() & 0xFF;
            assertEquals(
                         false,
                         seen.get(code),
                         "wire code 0x" + Integer.toHexString(code) + " is reused on " + t.name());
            seen.set(code);
        }
    }

    @Test
    void wireCodeIsNotJustOrdinal() {
        // PING is index 3 in declaration order but wire code 0x10 — pins the "explicit codes" intent.
        assertNotEquals(
                        FrameType.PING.ordinal(),
                        FrameType.PING.wireCode(),
                        "wire codes MUST NOT track enum ordinals — that would break stability on reordering");
    }

    @Test
    void fromWireCode_roundTripsForEveryConstant() {
        for (FrameType t : FrameType.values()) {
            assertSame(t, FrameType.fromWireCode(t.wireCode()));
        }
    }

    @Test
    void fromWireCode_unknownCode_returnsNull() {
        assertNull(FrameType.fromWireCode((byte) 0xFE));
        assertNull(FrameType.fromWireCode((byte) 0x00));
    }

    @Test
    void requireFromWireCode_unknownCode_throwsRingProtocolException() {
        RingProtocolException ex =
                assertThrows(
                             RingProtocolException.class, () -> FrameType.requireFromWireCode((byte) 0xFE));
        assertEquals(true, ex.getMessage().contains("0xFE"), ex.getMessage());
    }

    @Test
    void specificCodesAreFrozen_pinsThePublishedWireFormat() {
        // These are the published codes. If a future change renames or reorders constants this test
        // is the canary that the wire format would break.
        assertEquals((byte) 0x01, FrameType.HELLO.wireCode());
        assertEquals((byte) 0x02, FrameType.HELLO_ACK.wireCode());
        assertEquals((byte) 0x03, FrameType.PEER_LEFT.wireCode());
        assertEquals((byte) 0x10, FrameType.PING.wireCode());
        assertEquals((byte) 0x11, FrameType.PONG.wireCode());
        assertEquals((byte) 0x20, FrameType.EVENT.wireCode());
        assertEquals((byte) 0x30, FrameType.COMMAND_REQ.wireCode());
        assertEquals((byte) 0x31, FrameType.COMMAND_RESP.wireCode());
        assertEquals((byte) 0x40, FrameType.QUERY_REQ.wireCode());
        assertEquals((byte) 0x41, FrameType.QUERY_RESP.wireCode());
        assertEquals((byte) 0x50, FrameType.SAGA_STATE.wireCode());
        assertEquals((byte) 0x60, FrameType.LEASE_REQ.wireCode());
        assertEquals((byte) 0x61, FrameType.LEASE_GRANT.wireCode());
        assertEquals((byte) 0x70, FrameType.OUTBOX_REPLAY_REQ.wireCode());
        assertEquals((byte) 0x71, FrameType.OUTBOX_REPLAY_RESP.wireCode());
    }
}

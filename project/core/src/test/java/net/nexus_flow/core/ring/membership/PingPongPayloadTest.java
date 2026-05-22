package net.nexus_flow.core.ring.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PingPongPayload} round-trip and defensive caps. Wire shape used by every
 * heartbeat exchange — drift here would silently break failure detection.
 */
class PingPongPayloadTest {

    @Test
    void roundTrip_preservesSequenceAndSender() {
        PingPongPayload original = new PingPongPayload(12345L, PeerId.of("pod-prober-7"));
        PingPongPayload decoded  = PingPongPayload.decode(original.encode());
        assertEquals(original.sequence(), decoded.sequence());
        assertEquals(original.senderPeerId(), decoded.senderPeerId());
    }

    @Test
    void roundTrip_zeroSequence_isLegal() {
        PingPongPayload original = new PingPongPayload(0L, PeerId.of("p"));
        PingPongPayload decoded  = PingPongPayload.decode(original.encode());
        assertEquals(0L, decoded.sequence());
    }

    @Test
    void roundTrip_maxLongSequence() {
        PingPongPayload original = new PingPongPayload(Long.MAX_VALUE, PeerId.of("p"));
        PingPongPayload decoded  = PingPongPayload.decode(original.encode());
        assertEquals(Long.MAX_VALUE, decoded.sequence());
    }

    @Test
    void negativeSequence_isRejected() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new PingPongPayload(-1L, PeerId.of("p")));
    }

    @Test
    void decode_truncated_throws() {
        byte[] valid     = new PingPongPayload(1L, PeerId.of("p")).encode();
        byte[] truncated = Arrays.copyOfRange(valid, 0, valid.length - 1);
        assertThrows(RingProtocolException.class, () -> PingPongPayload.decode(truncated));
    }

    @Test
    void decode_trailingBytes_throws() {
        byte[] valid        = new PingPongPayload(1L, PeerId.of("p")).encode();
        byte[] withTrailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(RingProtocolException.class, () -> PingPongPayload.decode(withTrailing));
    }
}

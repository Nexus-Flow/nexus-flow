package net.nexus_flow.core.ring.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.wire.RingProtocol;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RingEventEnvelope} encode/decode round-trip and defensive caps. The envelope is
 * the load-bearing wire shape for cross-peer event delivery; drift in this serializer would
 * silently fragment the cluster's event-fanout layer.
 */
class RingEventEnvelopeTest {

    private static RingEventEnvelope sample() {
        return new RingEventEnvelope(
                PeerId.of("pod-acme-7"),
                42L,
                "com.acme.OrderPlaced",
                "java-v1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "acme",
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
    }

    @Test
    void roundTrip_preservesEveryField() {
        RingEventEnvelope original = sample();
        RingEventEnvelope decoded  = RingEventEnvelope.decode(original.encode());
        assertEquals(original.sourcePeerId(), decoded.sourcePeerId());
        assertEquals(original.sourceOutboxSequence(), decoded.sourceOutboxSequence());
        assertEquals(original.payloadType(), decoded.payloadType());
        assertEquals(original.codecId(), decoded.codecId());
        assertEquals(original.traceId(), decoded.traceId());
        assertEquals(original.correlationId(), decoded.correlationId());
        assertEquals(original.causationId(), decoded.causationId());
        assertEquals(original.tenantId(), decoded.tenantId());
        assertTrue(Arrays.equals(original.payloadBytes(), decoded.payloadBytes()));
    }

    @Test
    void roundTrip_withNullTenant_decodesNullNotEmptyString() {
        RingEventEnvelope original =
                new RingEventEnvelope(
                        PeerId.of("p"),
                        0L,
                        "t.X",
                        "c",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        new byte[0]);
        RingEventEnvelope decoded  = RingEventEnvelope.decode(original.encode());
        assertNull(decoded.tenantId());
    }

    @Test
    void emptyPayload_roundTrips() {
        RingEventEnvelope original =
                new RingEventEnvelope(
                        PeerId.of("p"),
                        0L,
                        "t.X",
                        "c",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "t",
                        new byte[0]);
        RingEventEnvelope decoded  = RingEventEnvelope.decode(original.encode());
        assertEquals(0, decoded.payloadBytes().length);
    }

    @Test
    void payloadType_emptyString_isRejectedAtConstruction() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new RingEventEnvelope(
                             PeerId.of("p"),
                             0L,
                             "",
                             "c",
                             UUID.randomUUID(),
                             UUID.randomUUID(),
                             UUID.randomUUID(),
                             null,
                             new byte[0]));
    }

    @Test
    void payloadType_longerThanCap_isRejectedAtConstruction() {
        String huge = "x".repeat(RingProtocol.MAX_TYPE_NAME_BYTES + 1);
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new RingEventEnvelope(
                             PeerId.of("p"),
                             0L,
                             huge,
                             "c",
                             UUID.randomUUID(),
                             UUID.randomUUID(),
                             UUID.randomUUID(),
                             null,
                             new byte[0]));
    }

    @Test
    void negativeSequence_isRejected() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new RingEventEnvelope(
                             PeerId.of("p"),
                             -1L,
                             "t.X",
                             "c",
                             UUID.randomUUID(),
                             UUID.randomUUID(),
                             UUID.randomUUID(),
                             null,
                             new byte[0]));
    }

    @Test
    void payloadBytesAccessor_returnsDefensiveCopy() {
        RingEventEnvelope env = sample();
        byte[]            a   = env.payloadBytes();
        byte[]            b   = env.payloadBytes();
        assertTrue(Arrays.equals(a, b));
        a[0] = (byte) 99;
        // Mutation on a does NOT affect b — defensive copy on every accessor call.
        assertNotEquals((byte) 99, b[0]);
    }

    @Test
    void decode_payloadLengthMismatch_throws() {
        // Encode then truncate the last byte — decoder should detect.
        byte[] valid     = sample().encode();
        byte[] truncated = Arrays.copyOfRange(valid, 0, valid.length - 1);
        assertThrows(RingProtocolException.class, () -> RingEventEnvelope.decode(truncated));
    }

    @Test
    void decode_trailingBytesAfterPayload_throws() {
        byte[] valid        = sample().encode();
        byte[] withTrailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(RingProtocolException.class, () -> RingEventEnvelope.decode(withTrailing));
    }
}

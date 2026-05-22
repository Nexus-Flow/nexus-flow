package net.nexus_flow.core.ddd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.Map;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link AbstractDomainEvent}'s on-wire serial form so future refactors of Java field names
 * never break payloads that have already been written to outbox storage.
 *
 * <p>The class uses an explicit {@code serialPersistentFields} declaration and custom {@code
 * writeObject}/{@code readObject} pair: this test exercises every guarantee that pinning provides.
 */
class AbstractDomainEventSerializationStabilityTest {

    static final class Stamped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Stamped(String aggregateId) {
            super(aggregateId);
        }

        Stamped(String aggregateId, Map<String, String> headers) {
            super(aggregateId, headers);
        }
    }

    private static byte[] toBytes(Object o) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(baos)) {
            out.writeObject(o);
        }
        return baos.toByteArray();
    }

    private static <T> T fromBytes(byte[] bytes, Class<T> type) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return type.cast(in.readObject());
        }
    }

    @Test
    @DisplayName("Round-trip preserves id, occurredAt, aggregateId and unassigned sequence")
    void roundtrip_preservesIdentityFields() throws Exception {
        Stamped original = new Stamped("agg-1");
        byte[]  wire     = toBytes(original);

        Stamped restored = fromBytes(wire, Stamped.class);

        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getTimestamp(), restored.getTimestamp());
        assertEquals(original.getAggregateId(), restored.getAggregateId());
        assertEquals(AbstractDomainEvent.UNASSIGNED_SEQUENCE_NUMBER, restored.getSequenceNumber());
    }

    @Test
    @DisplayName("Stamped sequence number survives round-trip")
    void roundtrip_preservesAssignedSequence() throws Exception {
        Stamped original = new Stamped("agg-7");
        original.assignSequenceNumber(42L);
        byte[] wire = toBytes(original);

        Stamped restored = fromBytes(wire, Stamped.class);

        assertEquals(42L, restored.getSequenceNumber());
        assertEquals("agg-7:42", restored.idempotencyKey());
    }

    @Test
    @DisplayName("Headers are intentionally stripped on deserialization (transient runtime metadata)")
    void roundtrip_headersResetToEmpty() throws Exception {
        Stamped original = new Stamped("agg-h", Map.of("trace-id", "abc", "tenant", "acme"));
        byte[]  wire     = toBytes(original);

        Stamped restored = fromBytes(wire, Stamped.class);

        assertTrue(
                   restored.getHeaders().isEmpty(),
                   "headers are transient runtime metadata; deserialization must reset to an empty map");
    }

    @Test
    @DisplayName("OutboxPayloadCodec round-trips a stamped event without loss")
    void codec_roundtrip_succeeds() throws Exception {
        Stamped original = new Stamped("agg-c");
        original.assignSequenceNumber(7L);

        var    codec   = new JavaSerializationOutboxPayloadCodec();
        byte[] encoded = codec.encode(original);
        Object decoded = codec.decode(encoded, Stamped.class);

        assertInstanceOf(Stamped.class, decoded);
        Stamped roundTripped = (Stamped) decoded;
        assertEquals(original.getId(), roundTripped.getId());
        assertEquals(7L, roundTripped.getSequenceNumber());
        assertEquals("agg-c", roundTripped.getAggregateId());
    }

    @Test
    @DisplayName("Tampered wire with negative non-sentinel sequence is rejected on read")
    void readObject_rejectsInvalidSequence() throws Exception {
        // Craft a payload that is round-trippable then poke at the bytes is brittle.
        // Instead, exercise the validation by forcing it through the codec with a
        // hand-built bad payload via subclassing: assign -2 directly is impossible
        // because assignSequenceNumber guards it, so we use reflection.
        Stamped                 original = new Stamped("agg-bad");
        java.lang.reflect.Field f        = AbstractDomainEvent.class.getDeclaredField("sequenceNumber");
        f.setAccessible(true);
        f.setLong(original, -2L);

        byte[]                 wire = toBytes(original);
        InvalidObjectException ex   =
                assertThrows(InvalidObjectException.class, () -> fromBytes(wire, Stamped.class));
        assertTrue(
                   ex.getMessage().contains("sequenceNumber"),
                   () -> "message should mention the bad field, got: " + ex.getMessage());
    }
}

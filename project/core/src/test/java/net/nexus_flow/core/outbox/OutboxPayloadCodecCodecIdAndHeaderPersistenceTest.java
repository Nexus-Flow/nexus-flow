package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.Map;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Pins two serde-axis contracts:
 *
 * <ol>
 * <li>{@link OutboxPayloadCodec#codecId()} default returns the FQN, and the framework's {@link
 * JavaSerializationOutboxPayloadCodec} overrides it with a stable {@value
 * JavaSerializationOutboxPayloadCodec#CODEC_ID}. This is the discriminator adapter modules
 * persist alongside each row so multi-codec deployments can route the decode call to the
 * right codec — see {@link OutboxPayloadCodec} Javadoc for the full migration playbook.
 * <li>{@link AbstractDomainEvent#getHeaders()} is {@code transient} — the outbox round-trip
 * reconstructs the event with an EMPTY headers map. The canonical trace / correlation /
 * causation / tenant ids travel via {@link OutboxRecord} fields and are restored on replay;
 * application-specific header metadata is intentionally lost. Pinning this prevents a future
 * "let's just persist the headers map too" change from silently weakening the wire-format
 * minimalism without an explicit decision.
 * </ol>
 */
class OutboxPayloadCodecCodecIdAndHeaderPersistenceTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggregateId, Map<String, String> headers) {
            super(aggregateId, headers);
        }
    }

    static final class TickAggregate extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private final UUID        id;

        TickAggregate(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void tickWithHeaders(Map<String, String> headers) {
            recordEvent(new Tick(id.toString(), headers));
        }
    }

    @Test
    void defaultCodecId_returnsFqn() {
        OutboxPayloadCodec codec =
                new OutboxPayloadCodec() {
                                             @Override
                                             public byte[] encode(DomainEvent event) {
                                                 return new byte[0];
                                             }

                                             @Override
                                             public DomainEvent decode(byte[] bytes, Class<?> payloadType) {
                                                 throw new UnsupportedOperationException();
                                             }
                                         };
        String             id    = codec.codecId();
        assertNotNull(id);
        assertTrue(
                   id.contains("OutboxPayloadCodecCodecIdAndHeaderPersistenceTest"),
                   "default codecId() must return the FQN of the implementing class; got: " + id);
    }

    @Test
    void javaSerializationCodec_overridesWithStableIdentity() {
        JavaSerializationOutboxPayloadCodec codec = new JavaSerializationOutboxPayloadCodec();
        assertEquals(
                     "java-v1",
                     codec.codecId(),
                     "JavaSerializationOutboxPayloadCodec MUST return the stable \"java-v1\" identity so a"
                             + " future java-v2 (different filter / DoS caps) can coexist during migration");
        assertEquals(
                     "java-v1",
                     JavaSerializationOutboxPayloadCodec.CODEC_ID,
                     "public constant matches the instance method");
    }

    @Test
    void roundTripDoesNotPersistHeaders_evenIfEventHadThem() {
        TickAggregate       agg             = new TickAggregate(UUID.randomUUID());
        Map<String, String> originalHeaders = Map.of("trace-id", "abc-123", "custom", "user-value");
        agg.tickWithHeaders(originalHeaders);
        Tick original = (Tick) agg.drainEvents().getFirst();

        // Sanity: the in-memory original has the headers
        assertEquals(originalHeaders, original.getHeaders());

        // Round-trip through the codec
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        byte[]                              bytes   = codec.encode(original);
        DomainEvent                         decoded = codec.decode(bytes, Tick.class);

        assertEquals(
                     Map.of(),
                     decoded.getHeaders(),
                     "outbox round-trip MUST reconstruct an EMPTY headers map — headers are transient by"
                             + " design. Application-specific header values do not survive replay; consumers"
                             + " that need durable metadata persist it as a typed field on the event subclass.");

        // But the canonical identity is preserved
        assertEquals(original.getId(), decoded.getId());
        assertEquals(original.getAggregateId(), decoded.getAggregateId());
        assertEquals(original.idempotencyKey(), decoded.idempotencyKey());
    }
}

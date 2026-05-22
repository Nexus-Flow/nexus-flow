package net.nexus_flow.core.runtime.ids;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MessageId}, {@link CorrelationId}, {@link TraceId}, and {@link CausationId} identity
 * contracts and conversions.
 */
class RuntimeIdValueTypesTest {

    @Test
    void messageIdRandomReturnsDistinctValues() {
        Set<MessageId> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(MessageId.random());
        }
        assertEquals(100, ids.size(), "all random MessageIds must be distinct");
    }

    @Test
    void messageIdEqualsAndHashCodeContract() {
        UUID      sameUuid = UUID.randomUUID();
        MessageId id1      = new MessageId(sameUuid);
        MessageId id2      = new MessageId(sameUuid);

        assertEquals(id1, id2, "MessageIds with same UUID must be equal");
        assertEquals(id1.hashCode(), id2.hashCode(), "equal MessageIds must have equal hashCodes");
    }

    @Test
    void messageIdAsCausationReturnsIdWithSameValue() {
        MessageId   messageId = MessageId.random();
        CausationId causation = messageId.asCausation();

        assertEquals(
                     messageId.value(), causation.value(), "CausationId must have same UUID as MessageId");
    }

    @Test
    void correlationIdRandomReturnsDistinctValues() {
        Set<CorrelationId> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(CorrelationId.random());
        }
        assertEquals(100, ids.size(), "all random CorrelationIds must be distinct");
    }

    @Test
    void correlationIdEqualsAndHashCodeContract() {
        UUID          sameUuid = UUID.randomUUID();
        CorrelationId id1      = new CorrelationId(sameUuid);
        CorrelationId id2      = new CorrelationId(sameUuid);

        assertEquals(id1, id2, "CorrelationIds with same UUID must be equal");
        assertEquals(id1.hashCode(), id2.hashCode(), "equal CorrelationIds must have equal hashCodes");
    }

    @Test
    void traceIdRandomReturnsDistinctValues() {
        Set<TraceId> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(TraceId.random());
        }
        assertEquals(100, ids.size(), "all random TraceIds must be distinct");
    }

    @Test
    void traceIdEqualsAndHashCodeContract() {
        UUID    sameUuid = UUID.randomUUID();
        TraceId id1      = new TraceId(sameUuid);
        TraceId id2      = new TraceId(sameUuid);

        assertEquals(id1, id2, "TraceIds with same UUID must be equal");
        assertEquals(id1.hashCode(), id2.hashCode(), "equal TraceIds must have equal hashCodes");
    }

    @Test
    void causationIdRootSentinelHasZeroUuid() {
        assertEquals(new UUID(0L, 0L), CausationId.ROOT.value(), "ROOT sentinel must have zero UUID");
    }

    @Test
    void causationIdEqualsAndHashCodeContract() {
        UUID        sameUuid = UUID.randomUUID();
        CausationId id1      = new CausationId(sameUuid);
        CausationId id2      = new CausationId(sameUuid);

        assertEquals(id1, id2, "CausationIds with same UUID must be equal");
        assertEquals(id1.hashCode(), id2.hashCode(), "equal CausationIds must have equal hashCodes");
    }

    @Test
    void differentIdTypesWithSameUuidAreNotEqual() {
        UUID          sameUuid      = UUID.randomUUID();
        MessageId     messageId     = new MessageId(sameUuid);
        CorrelationId correlationId = new CorrelationId(sameUuid);
        TraceId       traceId       = new TraceId(sameUuid);
        CausationId   causationId   = new CausationId(sameUuid);

        assertNotEquals(messageId, correlationId, "different types are not equal");
        assertNotEquals(messageId, traceId, "different types are not equal");
        assertNotEquals(messageId, causationId, "different types are not equal");
        assertNotEquals(correlationId, traceId, "different types are not equal");
    }

    @Test
    void toStringReturnsUuidString() {
        UUID      uuid      = UUID.randomUUID();
        MessageId messageId = new MessageId(uuid);

        assertTrue(messageId.toString().contains(uuid.toString()), "toString must contain UUID");
    }
}

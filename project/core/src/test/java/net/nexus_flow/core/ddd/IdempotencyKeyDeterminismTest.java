package net.nexus_flow.core.ddd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the determinism of {@link DomainEvent#idempotencyKey()} derivation: the key is {@code
 * aggregateId + ":" + sequenceNumber}. Two consecutive recorded events produce distinct
 * idempotencyKeys. An event never recorded has no derivable key.
 */
class IdempotencyKeyDeterminismTest {

    static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Bumped(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void bump() {
            recordEvent(new Bumped("agg-42"));
        }
    }

    @Test
    void consecutive_records_yield_distinctKeys() {
        // Two consecutive recordEvent calls produce monotonically increasing sequenceNumbers
        Counter c = new Counter();
        c.bump();
        c.bump();

        List<DomainEvent> drained = c.drainEvents();
        assertEquals(2, drained.size());

        AbstractDomainEvent e0 = (AbstractDomainEvent) drained.get(0);
        AbstractDomainEvent e1 = (AbstractDomainEvent) drained.get(1);

        assertEquals(0L, e0.getSequenceNumber(), "first recorded event must carry sequenceNumber 0");
        assertEquals(1L, e1.getSequenceNumber(), "second recorded event must carry sequenceNumber 1");

        assertEquals("agg-42:0", e0.idempotencyKey());
        assertEquals("agg-42:1", e1.idempotencyKey());
        assertNotEquals(
                        e0.idempotencyKey(),
                        e1.idempotencyKey(),
                        " consecutive events MUST have distinct idempotencyKeys");
    }

    @Test
    void orphan_event_throwsUnsupportedOperation_onKey() {
        // Event constructed but never recorded has no derivable key
        Bumped                        orphan = new Bumped("agg-orphan");
        UnsupportedOperationException ex     =
                assertThrows(UnsupportedOperationException.class, orphan::idempotencyKey);
        assertTrue(
                   ex.getMessage().contains("idempotencyKey"),
                   "exception must explain WHY the key is missing; got: " + ex.getMessage());
    }
}

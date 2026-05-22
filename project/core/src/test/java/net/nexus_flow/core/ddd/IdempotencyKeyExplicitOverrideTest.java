package net.nexus_flow.core.ddd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serial;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a {@link DomainEvent} subclass overriding {@link DomainEvent#idempotencyKey()}
 * bypasses the default derivation. Events imported from external systems carry upstream dedup
 * handles that must be published verbatim.
 */
class IdempotencyKeyExplicitOverrideTest {

    /**
     * "External" event simulating an inbound webhook record. The upstream dedup handle is the
     * broker's message id, NOT a per-aggregate sequence number.
     */
    static final class ExternalPaymentReceived extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String      externalMessageId;

        ExternalPaymentReceived(String aggregateId, String externalMessageId) {
            super(aggregateId);
            this.externalMessageId = externalMessageId;
        }

        @Override
        public String idempotencyKey() {
            return "external:" + externalMessageId;
        }
    }

    @Test
    void override_returnsVerbatim_ignoresMissingSequence() {
        // No Aggregate.recordEvent call — override short-circuits the default derivation
        ExternalPaymentReceived e = new ExternalPaymentReceived("wallet-7", "msg-abc-123");

        assertEquals(
                     "external:msg-abc-123",
                     e.idempotencyKey(),
                     " overridden idempotencyKey() must be respected verbatim, "
                             + "NOT post-processed with the default aggregate:sequence template");
        assertEquals(
                     AbstractDomainEvent.UNASSIGNED_SEQUENCE_NUMBER,
                     e.getSequenceNumber(),
                     "external event was never recorded on an aggregate; sequenceNumber stays unassigned");
    }

    @Test
    void override_isStable_forSameUpstream() {
        // Two instances with the same upstream handle must produce the same key
        ExternalPaymentReceived a = new ExternalPaymentReceived("w-7", "msg-1");
        ExternalPaymentReceived b = new ExternalPaymentReceived("w-7", "msg-1");

        assertEquals(
                     a.idempotencyKey(),
                     b.idempotencyKey(),
                     "Same upstream handle MUST yield the same idempotencyKey");
    }
}

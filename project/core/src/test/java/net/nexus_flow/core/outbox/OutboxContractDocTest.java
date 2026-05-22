package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * commit (d) — executable reference of the happy-path cycle: {@code append} → {@code claimBatch} →
 * {@code markPublished}.
 *
 * <p>This test is intentionally "documental": its purpose is to be the one place an integrator can
 * read to understand the storage contract end-to-end without digging through every other test in
 * this package.
 */
class OutboxContractDocTest {

    static final class FundsTransferred extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        FundsTransferred(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Account extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void transfer() {
            recordEvent(new FundsTransferred("acct-42"));
        }
    }

    @Test
    void appendThenClaimThenMarkPublished_yieldsPublishedRowWithCleanError() {
        // Wall-clock fixed for determinism — the storage uses this clock
        // for lastAttemptAt on transitions.
        Instant               t0      = Instant.parse("2026-05-19T17:00:00Z");
        Clock                 clock   = Clock.fixed(t0, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        // (1) Aggregate produces 1 event; we drain.
        Account a = new Account();
        a.transfer();
        List<DomainEvent> drained = a.drainEvents();
        assertEquals(1, drained.size());

        // (2)will call this between persistence and fan-out.
        ExecutionContext ctx = ExecutionContext.root();
        OutboxAppender.appendDrainedEvents(drained, ctx, storage, clock);

        // (3) Theworker would loop on this.
        List<OutboxRecord> batch = storage.claimBatch(10, t0);
        assertEquals(1, batch.size());
        OutboxRecord claimed = batch.getFirst();
        assertEquals(OutboxStatus.IN_FLIGHT, claimed.status());

        // (4) Worker pushes to the online bus, success → markPublished.
        storage.markPublished(claimed.outboxId());

        // (5) Final state pinned for documentation purposes.
        OutboxRecord finalRow = storage.findById(claimed.outboxId());
        assertEquals(OutboxStatus.PUBLISHED, finalRow.status());
        assertNull(finalRow.lastError());
        assertEquals(1, finalRow.attempts());
        assertEquals(t0, finalRow.lastAttemptAt());
        assertNull(finalRow.nextRetryAt());
        assertEquals("acct-42:0", finalRow.idempotencyKey().value());

        // (6) The row will not be re-claimed.
        assertEquals(0, storage.claimBatch(10, t0.plusSeconds(86_400)).size());
    }
}

package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link OutboxAppender#appendDrainedEvents} persists the idempotency key verbatim
 * for every event, alongside the trace / correlation / causation / message ids of the active {@link
 * ExecutionContext}.
 */
class OutboxAppendIdempotencyKeyPersistedTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void tick() {
            recordEvent(new Tick("agg-7"));
        }
    }

    @Test
    void threeEventsDrained_producesThreePendingRowsWithMonotonicSequenceAndSameContextIds() {
        // GIVEN: aggregate that records 3 events
        Counter c = new Counter();
        c.tick();
        c.tick();
        c.tick();
        List<DomainEvent> drained = c.drainEvents();
        assertEquals(3, drained.size());

        // AND: a context and a deterministic clock
        ExecutionContext ctx   = ExecutionContext.root();
        Instant          fixed = Instant.parse("2026-05-19T10:15:30Z");
        Clock            clock = Clock.fixed(fixed, ZoneOffset.UTC);

        // AND: an empty outbox storage
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        // WHEN: drain → outbox append
        OutboxAppender.appendDrainedEvents(drained, ctx, storage, clock);

        // THEN: storage holds exactly 3 rows
        List<OutboxRecord> rows =
                storage.snapshot().stream()
                        .sorted(Comparator.comparingLong(OutboxRecord::sequenceNo))
                        .toList();
        assertEquals(3, rows.size());

        // AND: sequenceNo is monotonic 0,1,2
        assertEquals(0L, rows.get(0).sequenceNo());
        assertEquals(1L, rows.get(1).sequenceNo());
        assertEquals(2L, rows.get(2).sequenceNo());

        // AND: idempotencyKey is thecanonical "aggregateId:sequenceNo"
        assertEquals("agg-7:0", rows.get(0).idempotencyKey().value());
        assertEquals("agg-7:1", rows.get(1).idempotencyKey().value());
        assertEquals("agg-7:2", rows.get(2).idempotencyKey().value());

        // AND: every row carries the same trace/correlation ids
        for (OutboxRecord r : rows) {
            assertEquals(ctx.traceId(), r.traceId());
            assertEquals(ctx.correlationId(), r.correlationId());
            assertEquals(ctx.causationId(), r.causationId());
            assertEquals(ctx.messageId(), r.messageId());
            assertEquals(Tick.class.getName(), r.aggregateType());
            assertEquals("agg-7", r.aggregateId());
            assertEquals(Tick.class, r.payloadType());
            assertNotNull(r.payloadBytes());
            assertEquals(0, r.payloadBytes().length, "leaves payload empty");
            assertEquals(fixed, r.recordedAt());
            assertEquals(OutboxStatus.PENDING, r.status());
            assertEquals(0, r.attempts());
            assertNull(r.lastError());
            assertNull(r.lastAttemptAt());
            assertNull(r.nextRetryAt());
        }

        // AND: outboxIds are pairwise distinct
        Set<OutboxId> ids = new HashSet<>();
        for (OutboxRecord r : rows)
            ids.add(r.outboxId());
        assertEquals(3, ids.size(), "outboxIds must be unique");
    }
}

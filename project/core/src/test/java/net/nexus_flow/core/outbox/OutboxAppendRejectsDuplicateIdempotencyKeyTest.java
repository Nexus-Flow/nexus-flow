package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

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
 * commit (b) — re-appending an event whose {@link IdempotencyKey} already exists with a
 * non-terminal status raises {@link OutboxDuplicateKeyException} and leaves the original row
 * untouched.
 */
class OutboxAppendRejectsDuplicateIdempotencyKeyTest {

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
            recordEvent(new Bumped("agg-1"));
        }
    }

    @Test
    void secondAppendOfSameKey_throwsAndPreservesOriginal() {
        Counter c1 = new Counter();
        c1.bump();
        DomainEvent first = c1.drainEvents().getFirst();

        // Build a second event with the samekey (agg-1:0)
        // by recording on a fresh aggregate.
        Counter c2 = new Counter();
        c2.bump();
        DomainEvent second = c2.drainEvents().getFirst();
        assertEquals(
                     first.idempotencyKey(), second.idempotencyKey(), "both events must share the canonicalkey");

        ExecutionContext      ctx     = ExecutionContext.root();
        Clock                 clock   = Clock.fixed(Instant.parse("2026-05-19T11:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        OutboxAppender.appendDrainedEvents(List.of(first), ctx, storage, clock);
        OutboxRecord originalRow = storage.snapshot().getFirst();

        OutboxDuplicateKeyException ex =
                assertThrows(
                             OutboxDuplicateKeyException.class,
                             () -> OutboxAppender.appendDrainedEvents(List.of(second), ctx, storage, clock));

        assertEquals(first.idempotencyKey(), ex.idempotencyKey().value());

        // Storage still holds exactly the original row, unchanged.
        assertEquals(1, storage.size());
        assertSame(originalRow, storage.snapshot().getFirst());
    }
}

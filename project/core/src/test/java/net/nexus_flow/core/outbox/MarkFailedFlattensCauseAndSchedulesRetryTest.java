package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * commit (c) — {@link OutboxStorage#markFailed(OutboxId, Throwable, Instant)} flattens the cause
 * (including caused-by chain) into {@code lastError}, increments {@code attempts}, sets {@code
 * lastAttemptAt} and reschedules the row back to PENDING with the supplied {@code nextRetryAt}.
 */
class MarkFailedFlattensCauseAndSchedulesRetryTest {

    @Test
    void markFailed_increasesAttempts_setsFlattenedLastError_andSchedulesNextRetry() {
        Instant               now     = Instant.parse("2026-05-19T14:00:00Z");
        Clock                 clock   = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        storage.append(OutboxFixtures.pending("agg-fail", 0, now.minusSeconds(5)));
        List<OutboxRecord> claimed = storage.claimBatch(1, now);
        OutboxId           id      = claimed.getFirst().outboxId();

        Throwable        root      = new IllegalStateException("downstream broker offline");
        RuntimeException composite = new RuntimeException("dispatch failed", root);

        Instant nextRetry = now.plusSeconds(30);
        storage.markFailed(id, composite, nextRetry);

        OutboxRecord row = storage.findById(id);
        assertNotNull(row);
        assertEquals(OutboxStatus.PENDING, row.status());
        assertEquals(1, row.attempts());
        assertEquals(nextRetry, row.nextRetryAt());
        assertEquals(now, row.lastAttemptAt());

        String le = row.lastError();
        assertNotNull(le);
        assertTrue(le.contains("RuntimeException"), "must include outer exception type");
        assertTrue(le.contains("dispatch failed"), "must include outer message");
        assertTrue(le.contains("Caused by"), "must include caused-by chain");
        assertTrue(le.contains("IllegalStateException"), "must include root exception type");
        assertTrue(le.contains("downstream broker offline"), "must include root message");
        // The flattened error must include a stack trace — class name followed
        // by .java or "at " line is good enough.
        assertTrue(le.contains("at "), "must include stack-trace frames");

        // A subsequent claim at now (nextRetry has not yet elapsed) must NOT
        // return this row.
        assertEquals(0, storage.claimBatch(10, now).size());
        // But a claim at or after nextRetry must.
        assertEquals(1, storage.claimBatch(10, nextRetry).size());
    }
}

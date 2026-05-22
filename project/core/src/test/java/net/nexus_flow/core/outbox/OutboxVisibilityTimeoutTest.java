package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.junit.jupiter.api.Test;

class OutboxVisibilityTimeoutTest {

    private static final Instant T0    = Instant.parse("2026-05-25T10:00:00Z");
    private static final Clock   CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static OutboxRecord pendingRow(String key) {
        return new OutboxRecord(
                OutboxId.next(),
                new IdempotencyKey(key),
                "TestAgg",
                "agg-1",
                0L,
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                MessageId.random(),
                String.class,
                "test".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                T0,
                OutboxStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void sweep_recoversInFlightRowsOlderThanThreshold() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(CLOCK);
        storage.append(pendingRow("a"));
        storage.append(pendingRow("b"));

        // Claim both at T0.
        List<OutboxRecord> claimed = storage.claimBatch(10, T0);
        assertEquals(2, claimed.size());
        for (OutboxRecord r : claimed) {
            assertEquals(OutboxStatus.IN_FLIGHT, storage.findById(r.outboxId()).status());
        }

        // Sweep at T0+10s with stale-after=5s — both rows should recover.
        int recovered = storage.sweepStaleClaims(Duration.ofSeconds(5), T0.plusSeconds(10));
        assertEquals(2, recovered);
        for (OutboxRecord r : claimed) {
            assertEquals(OutboxStatus.PENDING, storage.findById(r.outboxId()).status());
        }
    }

    @Test
    void sweep_leavesFreshClaimsAlone() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(CLOCK);
        storage.append(pendingRow("a"));
        List<OutboxRecord> claimed = storage.claimBatch(10, T0);
        assertEquals(1, claimed.size());

        // Sweep at T0+1s with stale-after=30s — too fresh to recover.
        int recovered = storage.sweepStaleClaims(Duration.ofSeconds(30), T0.plusSeconds(1));
        assertEquals(0, recovered);
        assertEquals(OutboxStatus.IN_FLIGHT,
                     storage.findById(claimed.getFirst().outboxId()).status());
    }

    @Test
    void sweep_doesNotIncrementAttemptCounter() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(CLOCK);
        storage.append(pendingRow("a"));
        OutboxRecord claimed        = storage.claimBatch(10, T0).getFirst();
        int          attemptsBefore = claimed.attempts();

        storage.sweepStaleClaims(Duration.ofSeconds(1), T0.plusSeconds(10));
        OutboxRecord after = storage.findById(claimed.outboxId());
        assertEquals(OutboxStatus.PENDING, after.status());
        assertEquals(attemptsBefore, after.attempts(),
                     "sweep recovery MUST NOT increment the attempt counter — it is a liveness recovery,"
                             + " not a delivery failure");
    }

    @Test
    void sweep_validation_rejectsNonPositiveStaleAfter() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(CLOCK);
        assertThrows(IllegalArgumentException.class,
                     () -> storage.sweepStaleClaims(Duration.ZERO, T0));
        assertThrows(IllegalArgumentException.class,
                     () -> storage.sweepStaleClaims(Duration.ofSeconds(-1), T0));
    }

    @Test
    void markPublished_removesClaimedAtEntry_soSweepDoesNotResurrect() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(CLOCK);
        storage.append(pendingRow("a"));
        OutboxRecord claimed = storage.claimBatch(10, T0).getFirst();
        storage.markPublished(claimed.outboxId());

        // Sweep at far future — the published row has no claimedAt entry to sweep.
        int recovered = storage.sweepStaleClaims(Duration.ofMillis(1), T0.plusSeconds(60));
        assertEquals(0, recovered);
        assertEquals(OutboxStatus.PUBLISHED, storage.findById(claimed.outboxId()).status(),
                     "PUBLISHED row MUST NOT be regressed by the sweep");
    }

    @Test
    void deadLetterHandler_LOG_ONLY_doesNotThrow_onTerminalFailure() {
        OutboxRecord row = new OutboxRecord(
                OutboxId.next(),
                new IdempotencyKey("k"),
                "TestAgg",
                "agg-1",
                0L,
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                MessageId.random(),
                String.class,
                "test".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                T0,
                OutboxStatus.FAILED_TERMINAL,
                10,
                "boom",
                T0.plusSeconds(5),
                null,
                null,
                null);
        // Contract: LOG_ONLY logs at WARNING and never throws. assertDoesNotThrow makes
        // that explicit instead of a meaningless assertTrue(true).
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> DeadLetterHandler.LOG_ONLY.onTerminalFailure(row, new RuntimeException(
                "test")));
    }
}

package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * commit (b) — verifies that {@link OutboxStorage#claimBatch} only returns {@link
 * OutboxStatus#PENDING} rows whose {@code nextRetryAt} is null or {@code <= now}, in {@code
 * recordedAt} ascending order, and flips them atomically to {@link OutboxStatus#IN_FLIGHT}.
 */
class ClaimBatchRespectsNextRetryAtAndOrderTest {

    @Test
    void claimBatchReturnsOnlyEligibleRowsInRecordedOrderAndFlipsThemToInFlight() {
        Instant               now     = Instant.parse("2026-05-19T12:00:00Z");
        Clock                 clock   = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        // 5 rows: 2 PUBLISHED, 1 PENDING eligible (recordedAt earliest),
        // 1 PENDING not-yet-eligible (nextRetryAt in the future), 1 PENDING
        // with null nextRetryAt (recordedAt later).
        OutboxRecord pub1             = OutboxFixtures.published("agg-pub-1", 0, now.minusSeconds(100));
        OutboxRecord pub2             = OutboxFixtures.published("agg-pub-2", 0, now.minusSeconds(99));
        OutboxRecord pendingEligible  =
                OutboxFixtures.pending("agg-elig", 0, now.minusSeconds(50), now.minusSeconds(1));
        OutboxRecord pendingFuture    =
                OutboxFixtures.pending("agg-future", 0, now.minusSeconds(40), now.plusSeconds(3600));
        OutboxRecord pendingNullRetry =
                OutboxFixtures.pending("agg-null-retry", 0, now.minusSeconds(30), null);

        storage.append(pub1);
        storage.append(pub2);
        storage.append(pendingEligible);
        storage.append(pendingFuture);
        storage.append(pendingNullRetry);

        // WHEN
        List<OutboxRecord> claimed = storage.claimBatch(10, now);

        // THEN: exactly 2 rows, in recordedAt asc order.
        assertEquals(2, claimed.size(), "only the 2 eligible PENDING rows must be claimed");
        assertEquals("agg-elig", claimed.get(0).aggregateId());
        assertEquals("agg-null-retry", claimed.get(1).aggregateId());

        // AND: both flipped to IN_FLIGHT in storage.
        for (OutboxRecord c : claimed) {
            assertEquals(OutboxStatus.IN_FLIGHT, c.status());
            OutboxRecord persisted = storage.findById(c.outboxId());
            assertEquals(OutboxStatus.IN_FLIGHT, persisted.status());
        }

        // AND: the future-retry row stays PENDING.
        OutboxRecord futureAfter = storage.findById(pendingFuture.outboxId());
        assertEquals(OutboxStatus.PENDING, futureAfter.status());
        assertTrue(futureAfter.nextRetryAt().isAfter(now));

        // AND: published rows stay PUBLISHED.
        assertEquals(OutboxStatus.PUBLISHED, storage.findById(pub1.outboxId()).status());
        assertEquals(OutboxStatus.PUBLISHED, storage.findById(pub2.outboxId()).status());
    }
}

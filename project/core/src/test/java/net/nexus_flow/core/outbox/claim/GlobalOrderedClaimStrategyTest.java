package net.nexus_flow.core.outbox.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.nexus_flow.core.outbox.IdempotencyKey;
import net.nexus_flow.core.outbox.OutboxClaimStrategy;
import net.nexus_flow.core.outbox.OutboxId;
import net.nexus_flow.core.outbox.OutboxRecord;
import net.nexus_flow.core.outbox.OutboxStatus;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.junit.jupiter.api.Test;

/**
 * pins the {@link GlobalOrderedClaimStrategy} contract:
 *
 * <ol>
 * <li>{@code onAppend} of a PENDING row adds it to the index; non-PENDING rows are ignored.
 * <li>{@code claim} returns rows in canonical order — priority DESC, then partitionKey ASC,
 * then sequenceNo ASC.
 * <li>{@code onTransition} from PENDING to a terminal status removes the row from the index.
 * <li>{@code onTransition} from IN_FLIGHT back to PENDING re-inserts the row at its canonical
 * position.
 * <li>{@code nextRetryAt} in the future skips the row in the current claim cycle.
 * <li>{@link OutboxClaimStrategy.ClaimContext#SINGLE_WORKER} accepts every partition; a
 * multi-shard context filters as a defensive check.
 * </ol>
 */
class GlobalOrderedClaimStrategyTest {

    private static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void onAppend_pending_isIndexed() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        strategy.onAppend(pending("agg-1", 0L, T0, 0));
        strategy.onAppend(pending("agg-2", 0L, T0, 0));
        assertEquals(2, strategy.indexedPendingCount());
    }

    @Test
    void onAppend_nonPending_isIgnored() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        OutboxRecord               row      = pending("agg-1", 0L, T0, 0)
                .withStatus(OutboxStatus.IN_FLIGHT);
        strategy.onAppend(row);
        assertEquals(0, strategy.indexedPendingCount());
    }

    @Test
    void claim_returnsInPriorityDescThenPartitionAscOrder() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        // priority 0 / partition "a-agg" / seq 0
        OutboxRecord lowAggA = pending("a-agg", 0L, T0, 0);
        // priority 10 / partition "z-agg" / seq 0 — should claim first (priority DESC)
        OutboxRecord highAggZ = pending("z-agg", 0L, T0, 10);
        // priority 0 / partition "b-agg" / seq 0 — claims after high, before lowAggA? NO.
        // partitionKey ASC so "a-agg" before "b-agg" at the same priority.
        OutboxRecord lowAggB = pending("b-agg", 0L, T0, 0);
        strategy.onAppend(lowAggA);
        strategy.onAppend(highAggZ);
        strategy.onAppend(lowAggB);

        List<OutboxRecord> claimed = strategy.claim(
                                                    10,
                                                    T0,
                                                    OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                    candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));

        assertEquals(3, claimed.size());
        assertEquals(10, claimed.get(0).priority());
        assertEquals("z-agg", claimed.get(0).partitionKey());
        assertEquals(0, claimed.get(1).priority());
        assertEquals("a-agg", claimed.get(1).partitionKey());
        assertEquals(0, claimed.get(2).priority());
        assertEquals("b-agg", claimed.get(2).partitionKey());
    }

    @Test
    void claim_returnsInSequenceAscWithinSamePartition() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        strategy.onAppend(pending("same-agg", 5L, T0, 0));
        strategy.onAppend(pending("same-agg", 1L, T0, 0));
        strategy.onAppend(pending("same-agg", 3L, T0, 0));

        List<OutboxRecord> claimed = strategy.claim(
                                                    10,
                                                    T0,
                                                    OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                    candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));

        assertEquals(1L, claimed.get(0).sequenceNo());
        assertEquals(3L, claimed.get(1).sequenceNo());
        assertEquals(5L, claimed.get(2).sequenceNo());
    }

    @Test
    void claim_capsAtMax() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        for (int i = 0; i < 20; i++) {
            strategy.onAppend(pending("agg-" + i, 0L, T0, 0));
        }
        List<OutboxRecord> claimed = strategy.claim(
                                                    5,
                                                    T0,
                                                    OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                    candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));
        assertEquals(5, claimed.size());
    }

    @Test
    void claim_skipsRowsWithFutureNextRetryAt() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        Instant                    future   = T0.plusSeconds(60);
        strategy.onAppend(pendingWithRetry("agg-due", 0L, T0, 0, null));
        strategy.onAppend(pendingWithRetry("agg-late", 1L, T0, 0, future));

        List<OutboxRecord> claimed = strategy.claim(
                                                    10,
                                                    T0,
                                                    OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                    candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));

        assertEquals(1, claimed.size());
        assertEquals("agg-due", claimed.get(0).partitionKey());
    }

    @Test
    void claim_acceptsRowsWhereNextRetryAtIsAtCurrentInstant() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        strategy.onAppend(pendingWithRetry("agg-now", 0L, T0, 0, T0));

        List<OutboxRecord> claimed = strategy.claim(
                                                    10,
                                                    T0,
                                                    OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                    candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));

        assertEquals(1, claimed.size());
    }

    @Test
    void onTransition_pendingToPublished_removesFromIndex() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        OutboxRecord               before   = pending("agg-1", 0L, T0, 0);
        strategy.onAppend(before);
        OutboxRecord after = before.withStatus(OutboxStatus.PUBLISHED);
        strategy.onTransition(before, after);
        assertEquals(0, strategy.indexedPendingCount());
    }

    @Test
    void onTransition_inFlightBackToPending_reInsertsAtCanonicalPosition() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        OutboxRecord               original = pending("agg-1", 0L, T0, 0);
        strategy.onAppend(original);
        OutboxRecord inFlight = original.withStatus(OutboxStatus.IN_FLIGHT);
        // simulate claim flipping
        strategy.onTransition(original, inFlight);
        assertEquals(0, strategy.indexedPendingCount());

        // worker fails → asPending(null) re-pends the row
        OutboxRecord rePending = inFlight.asPending(null);
        strategy.onTransition(inFlight, rePending);
        assertEquals(1, strategy.indexedPendingCount());

        // claim picks it up
        List<OutboxRecord> claimed = strategy.claim(
                                                    10,
                                                    T0,
                                                    OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                    candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));
        assertEquals(1, claimed.size());
    }

    @Test
    void claim_returningNullDropsCandidateFromIndex() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        strategy.onAppend(pending("agg-1", 0L, T0, 0));
        strategy.onAppend(pending("agg-2", 0L, T0, 0));

        AtomicLong         calls   = new AtomicLong();
        List<OutboxRecord> claimed = strategy.claim(
                                                    10,
                                                    T0,
                                                    OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                    candidate -> {
                                                        calls.incrementAndGet();
                                                        return null;
                                                    });
        assertEquals(0, claimed.size());
        assertEquals(2, calls.get(), "claimer must be called for every candidate");
        assertEquals(0, strategy.indexedPendingCount(),
                     "stale candidates must be dropped from the index after a null claim");
    }

    @Test
    void claim_multiShardCtx_filtersByOwnedPartition() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        // Use partitionKeys whose hash spreads across two shards. Empirically with the
        // String.hashCode of these literals.
        strategy.onAppend(pending("part-A", 0L, T0, 0));
        strategy.onAppend(pending("part-B", 0L, T0, 0));
        strategy.onAppend(pending("part-C", 0L, T0, 0));
        strategy.onAppend(pending("part-D", 0L, T0, 0));

        OutboxClaimStrategy.ClaimContext shard0 = new OutboxClaimStrategy.ClaimContext(0, 2);
        OutboxClaimStrategy.ClaimContext shard1 = new OutboxClaimStrategy.ClaimContext(1, 2);

        List<OutboxRecord> claimed0 = new ArrayList<>(strategy.claim(
                                                                     10,
                                                                     T0,
                                                                     shard0,
                                                                     candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT)));
        List<OutboxRecord> claimed1 = new ArrayList<>(strategy.claim(
                                                                     10,
                                                                     T0,
                                                                     shard1,
                                                                     candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT)));

        assertEquals(4, claimed0.size() + claimed1.size(),
                     "every PENDING row must be claimed by exactly one shard");
        for (OutboxRecord row : claimed0) {
            assertEquals(0, shard0.shardFor(row.partitionKey()),
                         "shard 0 received row owned by shard "
                                 + shard0.shardFor(row.partitionKey()));
        }
        for (OutboxRecord row : claimed1) {
            assertEquals(1, shard1.shardFor(row.partitionKey()));
        }
    }

    @Test
    void onRemove_pending_dropsRow() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        OutboxRecord               row      = pending("agg-1", 0L, T0, 0);
        strategy.onAppend(row);
        strategy.onRemove(row);
        assertEquals(0, strategy.indexedPendingCount());
    }

    @Test
    void emptyIndex_claim_returnsEmptyList() {
        GlobalOrderedClaimStrategy strategy = new GlobalOrderedClaimStrategy();
        List<OutboxRecord>         claimed  = strategy.claim(
                                                             10,
                                                             T0,
                                                             OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                             candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));
        assertNotNull(claimed);
        assertTrue(claimed.isEmpty());
    }

    // ---------- fixtures ----------

    private static OutboxRecord pending(String partitionKey, long seq, Instant recordedAt, int priority) {
        return pendingWithRetry(partitionKey, seq, recordedAt, priority, null);
    }

    private static OutboxRecord pendingWithRetry(
            String partitionKey, long seq, Instant recordedAt, int priority, Instant nextRetryAt) {
        return new OutboxRecord(
                OutboxId.next(),
                IdempotencyKey.of(partitionKey + ":" + seq),
                "test.Event",
                partitionKey,
                seq,
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                MessageId.random(),
                Object.class,
                new byte[0],
                recordedAt,
                OutboxStatus.PENDING,
                0,
                null,
                null,
                nextRetryAt,
                null,
                null,
                priority,
                partitionKey);
    }
}

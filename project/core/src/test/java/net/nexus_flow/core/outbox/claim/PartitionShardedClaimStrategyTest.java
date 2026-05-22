package net.nexus_flow.core.outbox.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * pins the {@link PartitionShardedClaimStrategy} contract:
 *
 * <ol>
 * <li>Per-partition skiplist is materialised lazily on first {@code onAppend}.
 * <li>Multi-worker shards see disjoint claim spaces — N workers each claim only the
 * partitions hashing to their shard.
 * <li>FIFO within a partition is strict (sequenceNo ASC).
 * <li>{@code onTransition} OUT of PENDING removes the row; back IN re-inserts.
 * <li>{@code onRemove} drops the row outright.
 * </ol>
 */
class PartitionShardedClaimStrategyTest {

    private static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void onAppend_indexesByPartition() {
        PartitionShardedClaimStrategy strategy = new PartitionShardedClaimStrategy();
        strategy.onAppend(pending("part-A", 0L, 0));
        strategy.onAppend(pending("part-A", 1L, 0));
        strategy.onAppend(pending("part-B", 0L, 0));
        assertEquals(2, strategy.trackedPartitions());
        assertEquals(3, strategy.indexedPendingCount());
    }

    @Test
    void claim_returnsOnlyRowsOwnedByThisShard() {
        PartitionShardedClaimStrategy strategy = new PartitionShardedClaimStrategy();
        for (int i = 0; i < 100; i++) {
            strategy.onAppend(pending("part-" + i, 0L, 0));
        }

        OutboxClaimStrategy.ClaimContext shard0  = new OutboxClaimStrategy.ClaimContext(0, 4);
        List<OutboxRecord>               claimed = strategy.claim(
                                                                  1000,
                                                                  T0,
                                                                  shard0,
                                                                  candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));
        for (OutboxRecord row : claimed) {
            assertEquals(0, shard0.shardFor(row.partitionKey()));
        }
    }

    @Test
    void multiShard_disjointClaims_coverAllRows() {
        PartitionShardedClaimStrategy strategy = new PartitionShardedClaimStrategy();
        int                           rows     = 200;
        for (int i = 0; i < rows; i++) {
            strategy.onAppend(pending("part-" + i, 0L, 0));
        }
        Set<OutboxId> seen           = new HashSet<>();
        int           totalShards    = 4;
        int           totalCollected = 0;
        for (int shard = 0; shard < totalShards; shard++) {
            OutboxClaimStrategy.ClaimContext ctx     = new OutboxClaimStrategy.ClaimContext(shard, totalShards);
            List<OutboxRecord>               claimed = strategy.claim(
                                                                      1000,
                                                                      T0,
                                                                      ctx,
                                                                      candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));
            totalCollected += claimed.size();
            for (OutboxRecord row : claimed) {
                assertTrue(seen.add(row.outboxId()),
                           "row " + row.outboxId() + " claimed by more than one shard");
            }
        }
        assertEquals(rows, totalCollected,
                     "all rows must be claimed by exactly one shard across the sweep");
    }

    @Test
    void claim_withinPartition_isFifoBySequence() {
        PartitionShardedClaimStrategy strategy = new PartitionShardedClaimStrategy();
        strategy.onAppend(pending("same", 5L, 0));
        strategy.onAppend(pending("same", 1L, 0));
        strategy.onAppend(pending("same", 3L, 0));

        // Force ownership: build a single-shard context.
        OutboxClaimStrategy.ClaimContext singleShard = OutboxClaimStrategy.ClaimContext.SINGLE_WORKER;
        List<OutboxRecord>               claimed     = strategy.claim(
                                                                      10,
                                                                      T0,
                                                                      singleShard,
                                                                      candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));

        assertEquals(1L, claimed.get(0).sequenceNo());
        assertEquals(3L, claimed.get(1).sequenceNo());
        assertEquals(5L, claimed.get(2).sequenceNo());
    }

    @Test
    void onTransition_outOfPending_removesFromShard() {
        PartitionShardedClaimStrategy strategy = new PartitionShardedClaimStrategy();
        OutboxRecord                  before   = pending("part-A", 0L, 0);
        strategy.onAppend(before);
        strategy.onTransition(before, before.withStatus(OutboxStatus.PUBLISHED));
        assertEquals(0, strategy.indexedPendingCount());
    }

    @Test
    void onTransition_backToPending_reInsertsInShard() {
        PartitionShardedClaimStrategy strategy = new PartitionShardedClaimStrategy();
        OutboxRecord                  pending  = pending("part-A", 0L, 0);
        strategy.onAppend(pending);
        OutboxRecord inFlight = pending.withStatus(OutboxStatus.IN_FLIGHT);
        strategy.onTransition(pending, inFlight);
        assertEquals(0, strategy.indexedPendingCount());
        OutboxRecord rePending = inFlight.asPending(null);
        strategy.onTransition(inFlight, rePending);
        assertEquals(1, strategy.indexedPendingCount());
    }

    @Test
    void onRemove_pending_dropsRow() {
        PartitionShardedClaimStrategy strategy = new PartitionShardedClaimStrategy();
        OutboxRecord                  row      = pending("part-A", 0L, 0);
        strategy.onAppend(row);
        strategy.onRemove(row);
        assertEquals(0, strategy.indexedPendingCount());
    }

    @Test
    void claim_singleWorkerCtx_acceptsEveryPartition() {
        PartitionShardedClaimStrategy strategy = new PartitionShardedClaimStrategy();
        strategy.onAppend(pending("alpha", 0L, 0));
        strategy.onAppend(pending("beta", 0L, 0));
        strategy.onAppend(pending("gamma", 0L, 0));
        List<OutboxRecord> claimed = strategy.claim(
                                                    100,
                                                    T0,
                                                    OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                    candidate -> candidate.withStatus(OutboxStatus.IN_FLIGHT));
        assertEquals(3, claimed.size());
    }

    @Test
    void shardFor_isDeterministic_andSpreads() {
        OutboxClaimStrategy.ClaimContext ctx  = new OutboxClaimStrategy.ClaimContext(0, 4);
        Set<Integer>                     seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(ctx.shardFor("partition-" + i));
        }
        assertEquals(4, seen.size(),
                     "200 distinct partition keys must hit every shard at least once");
    }

    @Test
    void shardFor_consistentAcrossInvocations() {
        OutboxClaimStrategy.ClaimContext ctx = new OutboxClaimStrategy.ClaimContext(0, 4);
        int                              s1  = ctx.shardFor("repeatable");
        int                              s2  = ctx.shardFor("repeatable");
        assertEquals(s1, s2);
    }

    @Test
    void claimContext_validation() {
        // shardId out of range
        try {
            new OutboxClaimStrategy.ClaimContext(-1, 1);
            assertEquals(0, 1, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotEquals("", expected.getMessage());
        }
        // totalShards must be >= 1
        try {
            new OutboxClaimStrategy.ClaimContext(0, 0);
            assertEquals(0, 1, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotEquals("", expected.getMessage());
        }
    }

    // ---------- fixtures ----------

    private static OutboxRecord pending(String partitionKey, long seq, int priority) {
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
                T0,
                OutboxStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                null,
                priority,
                partitionKey);
    }
}

package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.nexus_flow.core.outbox.claim.GlobalOrderedClaimStrategy;
import net.nexus_flow.core.outbox.claim.PartitionShardedClaimStrategy;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.junit.jupiter.api.Test;

/**
 * Integration test pinning that {@link InMemoryOutboxStorage} composed with a {@link
 * PartitionShardedClaimStrategy} hands two simulated workers a disjoint claim space.
 */
class InMemoryOutboxStoragePartitionShardedTest {

    private static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void twoShardsClaimDisjointPartitions_andCoverEveryRow() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(
                Clock.fixed(T0, java.time.ZoneOffset.UTC),
                new PartitionShardedClaimStrategy());
        for (int i = 0; i < 50; i++) {
            storage.append(row("part-" + i, 0L));
        }

        OutboxClaimStrategy.ClaimContext shard0 = new OutboxClaimStrategy.ClaimContext(0, 2);
        OutboxClaimStrategy.ClaimContext shard1 = new OutboxClaimStrategy.ClaimContext(1, 2);

        List<OutboxRecord> claim0 = new ArrayList<>(storage.claimBatch(100, T0, shard0));
        List<OutboxRecord> claim1 = new ArrayList<>(storage.claimBatch(100, T0, shard1));

        assertEquals(50, claim0.size() + claim1.size(),
                     "every PENDING row must be claimed by exactly one shard");
        Set<OutboxId> seen = new HashSet<>();
        for (OutboxRecord row : claim0) {
            assertTrue(seen.add(row.outboxId()));
            assertEquals(0, shard0.shardFor(row.partitionKey()));
        }
        for (OutboxRecord row : claim1) {
            assertTrue(seen.add(row.outboxId()));
            assertEquals(1, shard1.shardFor(row.partitionKey()));
        }
    }

    @Test
    void singleWorkerCtx_returnsEveryRow_inCanonicalOrder() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(
                Clock.fixed(T0, java.time.ZoneOffset.UTC),
                new PartitionShardedClaimStrategy());
        storage.append(row("alpha", 0L));
        storage.append(row("alpha", 1L));
        storage.append(row("beta", 0L));

        List<OutboxRecord> claimed = storage.claimBatch(10, T0);
        assertEquals(3, claimed.size(),
                     "single-worker context must claim every row across every partition");
    }

    @Test
    void globalOrderedStrategy_isDefault_andRespectsClaimOrdering() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        assertTrue(storage.claimStrategy() instanceof GlobalOrderedClaimStrategy);
        storage.append(rowAt("agg-low", 0L, 0));
        storage.append(rowAt("agg-high", 0L, 100));
        List<OutboxRecord> claimed = storage.claimBatch(10, T0);
        assertEquals("agg-high", claimed.get(0).partitionKey(),
                     "higher-priority row must claim first under priority DESC");
        assertNotEquals(claimed.get(0).outboxId(), claimed.get(1).outboxId());
    }

    private OutboxRecord row(String partitionKey, long seq) {
        return rowAt(partitionKey, seq, 0);
    }

    private OutboxRecord rowAt(String partitionKey, long seq, int priority) {
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

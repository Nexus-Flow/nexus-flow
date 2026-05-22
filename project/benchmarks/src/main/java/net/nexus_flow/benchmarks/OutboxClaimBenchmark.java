package net.nexus_flow.benchmarks;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.outbox.IdempotencyKey;
import net.nexus_flow.core.outbox.OutboxClaimStrategy;
import net.nexus_flow.core.outbox.OutboxId;
import net.nexus_flow.core.outbox.OutboxRecord;
import net.nexus_flow.core.outbox.OutboxStatus;
import net.nexus_flow.core.outbox.claim.GlobalOrderedClaimStrategy;
import net.nexus_flow.core.outbox.claim.PartitionShardedClaimStrategy;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Pins the algorithmic improvement of the {@link OutboxClaimStrategy} SPI:
 *
 * <ul>
 *   <li>{@code scanThenSortBaseline} reproduces the obvious naive approach —
 *       {@link ConcurrentHashMap#values()} → {@code stream().sorted(...)} → {@code limit(K)} →
 *       O(N log N) per claim. This is a REFERENCE BENCHMARK only; no production code path runs
 *       this way.
 *   <li>{@code globalOrdered} delegates to the in-tree default strategy — single skiplist,
 *       O(K log N) per claim.
 *   <li>{@code partitionSharded} delegates to the partition-sharded strategy — per-partition
 *       skiplists, O(K log(N/P)) per shard.
 * </ul>
 *
 * <p>The benchmark sweeps the pending-row count and partition count parametrically; expect the
 * scan-then-sort variant to dominate the runtime at high N while the strategy variants stay
 * flat.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class OutboxClaimBenchmark {

    @Param({ "1000", "10000", "100000" })
    public int pendingRows;

    @Param({ "10", "100", "1000" })
    public int partitions;

    @Param({ "32", "128" })
    public int claimBatch;

    private ConcurrentHashMap<OutboxId, OutboxRecord> rowsByOutboxIdForBaseline;
    private GlobalOrderedClaimStrategy                globalStrategy;
    private PartitionShardedClaimStrategy             shardedStrategy;
    private Instant                                   now;

    private static final Comparator<OutboxRecord> CLAIM_ORDER_COMPARATOR_BASELINE =
            Comparator.comparingInt(OutboxRecord::priority).reversed()
                    .thenComparing(OutboxRecord::partitionKey)
                    .thenComparingLong(OutboxRecord::sequenceNo)
                    .thenComparing(OutboxRecord::recordedAt)
                    .thenComparing(OutboxRecord::outboxId);

    @Setup
    public void setup() {
        now                       = Instant.parse("2026-06-01T12:00:00Z");
        rowsByOutboxIdForBaseline = new ConcurrentHashMap<>(pendingRows * 2);
        globalStrategy            = new GlobalOrderedClaimStrategy();
        shardedStrategy           = new PartitionShardedClaimStrategy();
        for (int i = 0; i < pendingRows; i++) {
            String       partitionKey = "p-" + (i % partitions);
            int          priority     = i % 4;
            OutboxRecord row          = new OutboxRecord(
                                                        OutboxId.next(),
                                                        IdempotencyKey.of("k-" + i),
                                                        "test.Event",
                                                        partitionKey,
                                                        i,
                                                        TraceId.random(),
                                                        CorrelationId.random(),
                                                        CausationId.ROOT,
                                                        MessageId.random(),
                                                        Object.class,
                                                        new byte[0],
                                                        now,
                                                        OutboxStatus.PENDING,
                                                        0,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        priority,
                                                        partitionKey);
            rowsByOutboxIdForBaseline.put(row.outboxId(), row);
            globalStrategy.onAppend(row);
            shardedStrategy.onAppend(row);
        }
    }

    /**
     * Reference baseline — full scan, sort by claim order, then take the head. O(N log N) per
     * claim. No production code path runs this way; the benchmark exists ONLY so the strategy
     * improvements can be quantified against the obvious naive approach.
     */
    @Benchmark
    public void scanThenSortBaseline(Blackhole bh) {
        List<OutboxRecord> claimed = rowsByOutboxIdForBaseline.values().stream()
                .filter(r -> r.status() == OutboxStatus.PENDING)
                .filter(r -> r.nextRetryAt() == null || !r.nextRetryAt().isAfter(now))
                .sorted(CLAIM_ORDER_COMPARATOR_BASELINE)
                .limit(claimBatch)
                .toList();
        bh.consume(claimed);
    }

    /**
     * Single-skiplist strategy. Lock-free iteration over a head-of-skiplist range. The
     * "+ restore" suffix tracks that the benchmark re-appends the claimed rows so the index
     * stays full across iterations — the measured cost is therefore "claim K rows + restore K
     * rows" rather than pure claim, but K is bounded so the restoration is dominated by the
     * claim traversal at every realistic N.
     */
    @Benchmark
    public void globalOrdered(Blackhole bh) {
        List<OutboxRecord> claimed = globalStrategy.claim(
                                                         claimBatch,
                                                         now,
                                                         OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                         candidate -> candidate);
        bh.consume(claimed);
        for (OutboxRecord r : claimed) {
            globalStrategy.onAppend(r);
        }
    }

    /**
     * Per-partition skiplist strategy with 4 simulated shards. Claims only the partitions owned
     * by shard 0. Restores claimed rows after each invocation to keep the index full (see
     * {@link #globalOrdered}).
     */
    @Benchmark
    public void partitionShardedFourShards(Blackhole bh) {
        OutboxClaimStrategy.ClaimContext ctx = new OutboxClaimStrategy.ClaimContext(0, 4);
        List<OutboxRecord> claimed = shardedStrategy.claim(
                                                          claimBatch,
                                                          now,
                                                          ctx,
                                                          candidate -> candidate);
        bh.consume(claimed);
        for (OutboxRecord r : claimed) {
            shardedStrategy.onAppend(r);
        }
    }

    /**
     * Partition-sharded strategy with a single shard — measures the overhead of the per-
     * partition iteration without sharding benefit, so the comparison against
     * {@link #globalOrdered} isolates the per-partition map cost.
     */
    @Benchmark
    public void partitionShardedSingleShard(Blackhole bh) {
        List<OutboxRecord> claimed = shardedStrategy.claim(
                                                          claimBatch,
                                                          now,
                                                          OutboxClaimStrategy.ClaimContext.SINGLE_WORKER,
                                                          candidate -> candidate);
        bh.consume(claimed);
        for (OutboxRecord r : claimed) {
            shardedStrategy.onAppend(r);
        }
    }
}

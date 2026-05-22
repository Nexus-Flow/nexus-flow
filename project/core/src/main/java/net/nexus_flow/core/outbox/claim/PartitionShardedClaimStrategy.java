package net.nexus_flow.core.outbox.claim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import net.nexus_flow.core.outbox.OutboxClaimStrategy;
import net.nexus_flow.core.outbox.OutboxRecord;
import net.nexus_flow.core.outbox.OutboxStatus;

/**
 * Multi-worker claim strategy that shards the {@link OutboxStatus#PENDING} index by
 * {@link OutboxRecord#partitionKey()}. Each worker passes its
 * {@link OutboxClaimStrategy.ClaimContext} carrying {@code (shardId, totalShards)} and the
 * strategy returns only rows whose partition hashes to that worker's shard — giving N
 * workers a disjoint claim space with zero cross-worker contention on the storage's CAS.
 *
 * <h2>Why partition sharding</h2>
 *
 * Single-worker {@link GlobalOrderedClaimStrategy} is the right default; multi-worker
 * deployments routing millions of dispatches per second per JVM hit two ceilings on the
 * global-ordered index:
 *
 * <ul>
 * <li>Every worker iterates the SAME skiplist; index contention degrades to a serial
 * scan even on a perfectly fair scheduler.
 * <li>Per-row CAS racing wastes work: at high contention the loser of every race
 * continues iterating, scanning rows the winner already took.
 * </ul>
 *
 * Sharding by consistent hash of {@code partitionKey} removes both ceilings: a row appears
 * in exactly one shard's index, owned by exactly one worker, claimed without contention.
 *
 * <h2>Complexity</h2>
 *
 * Let {@code N} be total PENDING rows, {@code P} the count of distinct partitions, {@code S}
 * the total shard count, and {@code K} the requested claim batch.
 *
 * <table>
 * <caption>operation costs</caption>
 * <tr><th>Operation</th> <th>Cost</th></tr>
 * <tr><td>{@code onAppend}</td> <td>O(log(N/P)) — one per-partition skiplist add</td></tr>
 * <tr><td>{@code onTransition}</td> <td>O(log(N/P)) — remove + optional re-add</td></tr>
 * <tr><td>{@code claim(K)}</td> <td>O(K · log(N/P)) — iterate this shard's partitions
 * in priority-then-partition-key order, claim until
 * {@code K}; the {@code P/S} factor on partition count
 * drops out because the iteration stops at {@code K}</td></tr>
 * </table>
 *
 * <h2>Ordering note</h2>
 *
 * Within a single partition the canonical {@code (priority DESC, sequenceNo ASC, …)} order is
 * strict. Across partitions OWNED BY THE SAME WORKER the strategy iterates partitions in
 * {@code partitionKey} ASC order then walks each partition's head — high-priority rows in a
 * later partition can be drained BEFORE low-priority rows in an earlier one only if the
 * earlier partition is exhausted (or empty) first. Operators that want strict global priority
 * ordering across partitions even within a shard wire a custom strategy; for the common
 * "worker pulls work fairly from its assigned partitions" pattern the per-partition strict
 * order is sufficient.
 *
 * <h2>Concurrency</h2>
 *
 * Same lock-free skiplist primitives as {@link GlobalOrderedClaimStrategy}. The per-partition
 * index lookup uses {@link ConcurrentHashMap#computeIfAbsent} to materialise the per-partition
 * skiplist lazily; the {@code computeIfAbsent} pre-installs the skiplist before any reader can
 * observe a partially-built index. Multi-worker concurrent appends to different partitions
 * are fully parallel; appends to the SAME partition contend only on that partition's skiplist
 * (skiplist {@code add} is CAS-based, no global lock).
 */
public final class PartitionShardedClaimStrategy implements OutboxClaimStrategy {

    private final ConcurrentHashMap<String, ConcurrentSkipListSet<OutboxRecord>> indexByPartition =
            new ConcurrentHashMap<>();

    private ConcurrentSkipListSet<OutboxRecord> indexFor(String partitionKey) {
        return indexByPartition.computeIfAbsent(
                                                partitionKey,
                                                _ -> new ConcurrentSkipListSet<>(CLAIM_ORDER_COMPARATOR));
    }

    @Override
    public void onAppend(OutboxRecord row) {
        Objects.requireNonNull(row, "row");
        if (row.status() == OutboxStatus.PENDING) {
            indexFor(row.partitionKey()).add(row);
        }
    }

    @Override
    public void onTransition(OutboxRecord before, OutboxRecord after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        boolean wasPending = before.status() == OutboxStatus.PENDING;
        boolean nowPending = after.status() == OutboxStatus.PENDING;
        if (wasPending) {
            ConcurrentSkipListSet<OutboxRecord> shard = indexByPartition.get(before.partitionKey());
            if (shard != null) {
                shard.remove(before);
            }
        }
        if (nowPending) {
            indexFor(after.partitionKey()).add(after);
        }
    }

    @Override
    public void onRemove(OutboxRecord row) {
        Objects.requireNonNull(row, "row");
        if (row.status() == OutboxStatus.PENDING) {
            ConcurrentSkipListSet<OutboxRecord> shard = indexByPartition.get(row.partitionKey());
            if (shard != null) {
                shard.remove(row);
            }
        }
    }

    @Override
    public List<OutboxRecord> claim(int max, Instant now, ClaimContext ctx, ClaimAttempt claimer) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be >= 1: " + max);
        }
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(claimer, "claimer");
        List<OutboxRecord> claimed = new ArrayList<>(Math.min(max, 16));
        // Iterate partitions in deterministic order so two workers with the same ctx see the
        // same partition sequence (helps reasoning + repeatable benchmarks). The
        // ConcurrentHashMap's entrySet does NOT guarantee order; sorting the key set is the
        // cheapest fix and amortises with the iteration cost.
        List<String> ownedKeys = new ArrayList<>(indexByPartition.size());
        for (String partitionKey : indexByPartition.keySet()) {
            if (ctx.ownsPartition(partitionKey)) {
                ownedKeys.add(partitionKey);
            }
        }
        ownedKeys.sort(null);

        for (String partitionKey : ownedKeys) {
            if (claimed.size() >= max) {
                break;
            }
            ConcurrentSkipListSet<OutboxRecord> shard = indexByPartition.get(partitionKey);
            if (shard == null) {
                continue;
            }
            Iterator<OutboxRecord> iter = shard.iterator();
            while (iter.hasNext() && claimed.size() < max) {
                OutboxRecord candidate = iter.next();
                if (candidate.nextRetryAt() != null && candidate.nextRetryAt().isAfter(now)) {
                    continue;
                }
                OutboxRecord flipped = claimer.tryClaim(candidate);
                if (flipped != null) {
                    claimed.add(flipped);
                    shard.remove(candidate);
                } else {
                    shard.remove(candidate);
                }
            }
        }
        return claimed;
    }

    /** Diagnostic — number of distinct partitions tracked. */
    public int trackedPartitions() {
        return indexByPartition.size();
    }

    /** Diagnostic — total PENDING rows across every partition (may lag concurrent claims). */
    public int indexedPendingCount() {
        int total = 0;
        for (Map.Entry<String, ConcurrentSkipListSet<OutboxRecord>> e : indexByPartition.entrySet()) {
            total += e.getValue().size();
        }
        return total;
    }
}

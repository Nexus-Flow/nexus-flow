package net.nexus_flow.core.outbox.claim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListSet;
import net.nexus_flow.core.outbox.OutboxClaimStrategy;
import net.nexus_flow.core.outbox.OutboxRecord;
import net.nexus_flow.core.outbox.OutboxStatus;

/**
 * Default single-worker claim strategy. Maintains a single
 * {@link ConcurrentSkipListSet} of every {@link OutboxStatus#PENDING} row ordered by the
 * canonical {@link OutboxClaimStrategy#CLAIM_ORDER_COMPARATOR}.
 *
 * <h2>Complexity</h2>
 *
 * <table>
 * <caption>operation costs</caption>
 * <tr><th>Operation</th> <th>Cost</th></tr>
 * <tr><td>{@code onAppend}</td> <td>O(log N) — single skiplist add</td></tr>
 * <tr><td>{@code onTransition}</td> <td>O(log N) — remove + optional re-add</td></tr>
 * <tr><td>{@code claim(K)}</td> <td>O(K · log N) — iterate skiplist head, try claim,
 * remove on success / on stale</td></tr>
 * </table>
 *
 * <p>Compare to the previous {@code Stream::sorted} implementation which paid O(N · log N)
 * per claim — 10×+ improvement for any non-trivial backlog and the difference between
 * "tests-only" and "viable for low-throughput production" at the InMemory backend.
 *
 * <h2>Concurrency</h2>
 *
 * Reads ({@code claim} iteration) are lock-free — {@link ConcurrentSkipListSet} returns
 * weakly-consistent iterators that tolerate concurrent insertions / removals. The
 * per-candidate atomic flip is the storage's responsibility (via {@link
 * OutboxClaimStrategy.ClaimAttempt}). Two workers racing to claim the same row each see one
 * succeed (the storage's CAS) and the loser drops its stale reference. Append and transition
 * mutations are lock-free too — skiplist {@code add} / {@code remove} use CAS internally.
 *
 * <p>Operating in a multi-worker deployment with this strategy is correct but wasteful: every
 * worker iterates the SAME global index, contending on the storage's per-row CAS. Multi-
 * worker deployments should use {@link PartitionShardedClaimStrategy} instead — it gives
 * disjoint claim spaces per shard.
 */
public final class GlobalOrderedClaimStrategy implements OutboxClaimStrategy {

    private final ConcurrentSkipListSet<OutboxRecord> pendingByOrder =
            new ConcurrentSkipListSet<>(CLAIM_ORDER_COMPARATOR);

    @Override
    public void onAppend(OutboxRecord row) {
        Objects.requireNonNull(row, "row");
        if (row.status() == OutboxStatus.PENDING) {
            pendingByOrder.add(row);
        }
    }

    @Override
    public void onTransition(OutboxRecord before, OutboxRecord after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        boolean wasPending = before.status() == OutboxStatus.PENDING;
        boolean nowPending = after.status() == OutboxStatus.PENDING;
        // The skiplist's equality is comparator-based: rows that compare equal on every
        // ordered field collide. We always remove the "before" reference (by identity-equal
        // contents) and re-add "after" — the comparator covers every field that can change
        // (priority, partitionKey, sequenceNo, recordedAt, outboxId). A PENDING→PENDING
        // transition typically means nextRetryAt changed; the skiplist comparator does not
        // include nextRetryAt, so the in-place row is still ordered correctly — but we
        // refresh the reference to keep the index pointing at the latest row instance.
        if (wasPending) {
            pendingByOrder.remove(before);
        }
        if (nowPending) {
            pendingByOrder.add(after);
        }
    }

    @Override
    public void onRemove(OutboxRecord row) {
        Objects.requireNonNull(row, "row");
        if (row.status() == OutboxStatus.PENDING) {
            pendingByOrder.remove(row);
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
        List<OutboxRecord>     claimed = new ArrayList<>(Math.min(max, 16));
        Iterator<OutboxRecord> iter    = pendingByOrder.iterator();
        while (iter.hasNext() && claimed.size() < max) {
            OutboxRecord candidate = iter.next();
            // Eligibility: PENDING (the iterator may have a stale view if a concurrent
            // worker flipped the row but did not remove from the index — handled by the
            // tryClaim returning null), and nextRetryAt has elapsed.
            if (candidate.nextRetryAt() != null && candidate.nextRetryAt().isAfter(now)) {
                continue;
            }
            // Partition sharding: this strategy is single-worker by design and accepts
            // every partition. Multi-worker callers that want sharding wire
            // PartitionShardedClaimStrategy instead — but a defensive check honours the
            // ctx so a sharded call against this strategy still returns the worker's
            // partitions only (slower than a sharded strategy, but correct).
            if (ctx.totalShards() > 1 && !ctx.ownsPartition(candidate.partitionKey())) {
                continue;
            }
            OutboxRecord flipped = claimer.tryClaim(candidate);
            if (flipped != null) {
                claimed.add(flipped);
                pendingByOrder.remove(candidate);
            } else {
                // Concurrent transition flipped the row out of PENDING; the index reference
                // is stale. Drop it so no future iteration revisits the row.
                pendingByOrder.remove(candidate);
            }
        }
        return claimed;
    }

    /** Diagnostic — current size of the PENDING index (may lag concurrent transitions). */
    public int indexedPendingCount() {
        return pendingByOrder.size();
    }
}

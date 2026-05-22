package net.nexus_flow.core.outbox;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pluggable claim-side index for an {@link OutboxStorage}. The default in-tree storage
 * ({@link InMemoryOutboxStorage}) delegates the ordered iteration that powers
 * {@link OutboxStorage#claimBatch(int, Instant)} to a strategy chosen at construction time.
 * Production adapters (JDBC, Redis, Kafka outbox tables) implement their own claim semantics
 * through the same SPI so the runtime can compose any storage backend with any concurrency
 * shape (single-worker, partition-sharded, leader-election-with-fanout, …).
 *
 * <h2>Why a strategy SPI</h2>
 *
 * Claim-side ordering and concurrency are orthogonal concerns from append-side persistence.
 * A test backend that holds rows in a {@link java.util.concurrent.ConcurrentHashMap} can serve
 * either:
 *
 * <ul>
 * <li>One worker, lock-free claim through a global ordered view — {@link
 * net.nexus_flow.core.outbox.claim.GlobalOrderedClaimStrategy}.
 * <li>N workers, each claiming a disjoint subset of partitions identified by a consistent
 * hash — {@link net.nexus_flow.core.outbox.claim.PartitionShardedClaimStrategy}.
 * </ul>
 *
 * Without the SPI, baking in either shape forces a refactor when the deployment grows. Adapter
 * modules pick the right shape for their backend (JDBC: {@code SELECT FOR UPDATE SKIP LOCKED}
 * naturally supports both modes; Redis: streams + consumer groups give partition sharding for
 * free; in-memory: skiplist for single-worker, per-partition skiplists for multi-worker).
 *
 * <h2>Comparator contract</h2>
 *
 * Every strategy MUST honour the canonical claim ordering exposed by
 * {@link #CLAIM_ORDER_COMPARATOR}: {@code (priority DESC, partitionKey ASC, sequenceNo ASC,
 * recordedAt ASC, outboxId ASC)}. Adapters that maintain an index on different fields
 * project to this ordering at claim time.
 *
 * <h2>Thread-safety</h2>
 *
 * Implementations MUST be safe for concurrent {@code onAppend}, {@code onTransition}, and
 * {@code claim} calls from different threads. The in-tree implementations are lock-free for
 * reads (claim iteration) and use lock-free / CAS primitives for writes.
 */
public interface OutboxClaimStrategy {

    /**
     * Canonical claim ordering used by every strategy. See class Javadoc for the precise
     * tuple; matches the JDBC adapter's expected index plan.
     */
    Comparator<OutboxRecord> CLAIM_ORDER_COMPARATOR =
            Comparator.comparingInt(OutboxRecord::priority).reversed()
                    .thenComparing(OutboxRecord::partitionKey)
                    .thenComparingLong(OutboxRecord::sequenceNo)
                    .thenComparing(OutboxRecord::recordedAt)
                    .thenComparing(OutboxRecord::outboxId);

    /**
     * Notify the strategy that a fresh {@link OutboxStatus#PENDING} row has been appended to
     * storage. The strategy adds it to its index.
     *
     * @param row the row just appended; never {@code null}; status is {@link
     *            OutboxStatus#PENDING}
     */
    void onAppend(OutboxRecord row);

    /**
     * Notify the strategy of a status transition on an existing row. The strategy uses this to
     * keep its index coherent — remove the row when leaving {@link OutboxStatus#PENDING},
     * re-insert it when returning to {@link OutboxStatus#PENDING}.
     *
     * @param before the row's state before the transition; never {@code null}
     * @param after  the row's state after the transition; never {@code null}; has the same
     *               {@link OutboxRecord#outboxId()} as {@code before}
     */
    void onTransition(OutboxRecord before, OutboxRecord after);

    /**
     * Notify the strategy that a row has been removed from storage entirely (e.g. by a
     * {@code purge}-style retention policy or a manual {@code remove}). Used to drop the
     * row from every index the strategy maintains. The default implementation calls {@link
     * #onTransition(OutboxRecord, OutboxRecord)} with the status flipped to {@link
     * OutboxStatus#PUBLISHED} — strategies that maintain row references keyed by the actual
     * record must override and explicitly remove.
     */
    default void onRemove(OutboxRecord row) {
        if (row.status() == OutboxStatus.PENDING) {
            onTransition(row, row.withStatus(OutboxStatus.PUBLISHED));
        }
    }

    /**
     * Claim up to {@code max} eligible rows. {@code claimer} is invoked for each candidate row
     * and returns the row's new state IF the claim succeeded (the storage's atomic
     * compare-and-set passed) or {@code null} IF the row was concurrently transitioned by
     * another worker / sweeper. The strategy uses the return value to keep its index
     * coherent: a {@code null} return means the candidate is no longer in {@link
     * OutboxStatus#PENDING} and SHOULD be removed from the strategy's index.
     *
     * <p>Implementations iterate their index in canonical order
     * ({@link #CLAIM_ORDER_COMPARATOR}), filter eligibility ({@link
     * OutboxRecord#nextRetryAt()} {@code null} or {@code <= now}), respect the
     * {@code ctx.partitionPredicate()} when present, and stop early once {@code max} claims
     * succeed.
     *
     * @param max     maximum number of rows to claim; must be {@code >= 1}
     * @param now     wall-clock instant against which {@code nextRetryAt} is evaluated
     * @param ctx     shard / partition context; never {@code null};
     *                {@link ClaimContext#SINGLE_WORKER} for the default single-worker case
     * @param claimer per-candidate atomic-claim invocation
     * @return the rows the {@code claimer} successfully transitioned, in iteration order;
     *         never {@code null}; size {@code <= max}
     */
    List<OutboxRecord> claim(int max, Instant now, ClaimContext ctx, ClaimAttempt claimer);

    /**
     * Per-candidate claim hook supplied by the storage. The strategy calls this for each
     * eligible candidate in iteration order; the storage performs the atomic
     * compare-and-set that flips the row to {@link OutboxStatus#IN_FLIGHT}.
     */
    @FunctionalInterface
    interface ClaimAttempt {
        /**
         * Try to atomically claim {@code candidate}. Returns the new state on success (status
         * {@link OutboxStatus#IN_FLIGHT}); returns {@code null} when the row was concurrently
         * transitioned by another worker / sweeper (in which case the strategy should drop
         * its stale reference).
         *
         * @param candidate the candidate row drawn from the strategy's index
         * @return the new state if claimed, {@code null} otherwise
         */
        OutboxRecord tryClaim(OutboxRecord candidate);
    }

    /**
     * Shard / partition context passed to {@link #claim(int, Instant, ClaimContext,
     * ClaimAttempt)}. {@link #SINGLE_WORKER} is the default for non-sharded callers; multi-
     * worker deployments construct one context per worker holding {@code (shardId,
     * totalShards)} and the strategy partitions the claim space accordingly.
     *
     * @param shardId     identifier of THIS worker's shard; in {@code [0, totalShards)}
     * @param totalShards total number of shards in the deployment; must be {@code >= 1}
     */
    record ClaimContext(int shardId, int totalShards) {

        /**
         * Default context for non-sharded callers: one worker owns the whole claim space.
         * Strategies that do not implement sharding interpret every context as this one.
         */
        public static final ClaimContext SINGLE_WORKER = new ClaimContext(0, 1);

        public ClaimContext {
            if (totalShards < 1) {
                throw new IllegalArgumentException(
                        "totalShards must be >= 1: " + totalShards);
            }
            if (shardId < 0 || shardId >= totalShards) {
                throw new IllegalArgumentException(
                        "shardId must be in [0, " + totalShards + "): " + shardId);
            }
        }

        /**
         * Hash {@code partitionKey} into one of the {@link #totalShards()} buckets. Used by
         * strategies that distribute partitions across workers via consistent hashing. The
         * canonical algorithm is the partition key's {@link String#hashCode()} folded to
         * {@code [0, totalShards)} via {@link Math#floorMod(int, int)}; adapters that need a
         * stronger hash (cross-JVM-stable, low-collision) override the strategy entirely.
         *
         * @param partitionKey the partition key to bucket; never {@code null}
         * @return the shard id (in {@code [0, totalShards)}) responsible for that partition
         */
        public int shardFor(String partitionKey) {
            Objects.requireNonNull(partitionKey, "partitionKey");
            return Math.floorMod(partitionKey.hashCode(), totalShards);
        }

        /** {@code true} when this context owns {@code partitionKey}. */
        public boolean ownsPartition(String partitionKey) {
            return shardFor(partitionKey) == shardId;
        }
    }
}

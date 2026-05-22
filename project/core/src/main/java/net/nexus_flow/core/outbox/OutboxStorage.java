package net.nexus_flow.core.outbox;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * persistent contract behind the outbox pattern.
 *
 * <p><strong>SPI hardening:</strong> Originally {@code sealed permits InMemoryOutboxStorage,
 * JdbcOutboxStorage}; the JDBC permit was a no-op placeholder while the real implementation was
 * being designed. With the split into a dedicated {@code nexus-flow-jdbc} module the placeholder no
 * longer earns its keep — keeping it would force every backend addition through a {@code core}
 * amendment, defeating the modular layout. The contract is therefore a regular interface so the
 * {@code nexus-flow-jdbc} module (and any future backend) can ship a real implementation directly.
 * {@link InMemoryOutboxStorage} remains the in-core default for tests / single-node demos.
 *
 * <p>The interface intentionally does not expose any transactional or lifecycle method: all five
 * operations are atomic from the caller's perspective. A JDBC backend will run them inside the
 * surrounding transaction; the in-memory backend uses concurrent collections.
 */
public interface OutboxStorage {

    Logger LOG = System.getLogger(OutboxStorage.class.getName());

    /**
     * Persist a freshly-recorded {@link OutboxRecord}.
     *
     * @throws OutboxDuplicateKeyException if a row with the same {@link IdempotencyKey} already
     *                                     exists with a status different from {@link OutboxStatus#FAILED_TERMINAL}. A {@code
     *     FAILED_TERMINAL}             row may be transparently overwritten — this is the manual-replay path.
     */
    void append(OutboxRecord record);

    /**
     * Bulk-append helper. The default implementation loops {@link #append(OutboxRecord)} once
     * per row, which is correct but pays one round-trip per row in any I/O-bound backend.
     * Adapters override to issue a single batched write (JDBC: {@code PreparedStatement
     * .addBatch + executeBatch} under one transaction; Redis: a pipeline of {@code SET NX};
     * Kafka outbox tables read by Debezium: a single transaction commit so all rows surface
     * to the CDC reader together).
     *
     * <p>Semantics: each row is appended in iteration order. The same idempotency rule applies
     * per row — duplicate {@link IdempotencyKey}s in the same batch surface as {@link
     * OutboxDuplicateKeyException} from the offending append.
     *
     * <p>Default implementation: linear for-loop over {@link #append(OutboxRecord)}. Throws on
     * the first duplicate or storage error; earlier rows already appended are NOT rolled back
     * by the default implementation. Adapters override with transaction-aware semantics when
     * cross-row atomicity is required.
     *
     * @param records the rows to append in order; never {@code null}; empty is a no-op
     * @throws NullPointerException        if {@code records} or any element is {@code null}
     * @throws OutboxDuplicateKeyException if any row's idempotency key is already present
     */
    default void appendBatch(java.util.List<OutboxRecord> records) {
        java.util.Objects.requireNonNull(records, "records");
        for (OutboxRecord record : records) {
            append(java.util.Objects.requireNonNull(record, "record"));
        }
    }

    /**
     * Bulk version of {@link #markPublished(OutboxId)}. The default loops one call per id,
     * which is correct but costs one round-trip per id in I/O-bound backends. Adapters
     * override with a bulk {@code UPDATE ... WHERE id IN (?, ?, ...)} (JDBC), {@code MSET}
     * (Redis), or a single transaction wrapping multiple updates.
     *
     * <p>Atomicity: the default implementation does NOT wrap the per-id calls in a
     * transaction — a failure mid-batch leaves earlier rows transitioned. Adapter overrides
     * SHOULD make the batch atomic so a worker failure between calls cannot half-publish.
     *
     * @param ids the rows to mark published in iteration order; never {@code null}; empty is
     *            a no-op
     * @throws NullPointerException if {@code ids} or any element is {@code null}
     */
    default void markPublishedBatch(java.util.List<OutboxId> ids) {
        java.util.Objects.requireNonNull(ids, "ids");
        for (OutboxId id : ids) {
            markPublished(java.util.Objects.requireNonNull(id, "id"));
        }
    }

    /**
     * Approximate number of {@link OutboxStatus#PENDING} rows currently queued. Used by the
     * runtime's append-side backpressure check to decide whether to accept a fresh batch.
     * Implementations SHOULD return a fast, possibly-stale count — exactness is not required;
     * the value is a hint, not a guarantee.
     *
     * <ul>
     * <li>JDBC adapters: {@code SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'} or a
     * cached {@code pg_class.reltuples} estimate when exactness is not worth the cost.
     * <li>In-memory: linear scan of the local map filtered by status.
     * <li>Redis: {@code XLEN} on the PENDING stream / {@code SCARD} on the PENDING set.
     * </ul>
     *
     * <p><strong>Default implementation:</strong> returns {@code -1L} (unknown). The runtime
     * interprets {@code -1L} as "saturation cannot be evaluated" and applies no backpressure.
     * Operators who want backpressure MUST wire a storage backend that overrides this.
     *
     * @return approximate PENDING count, or {@code -1L} when unsupported by the backend
     */
    default long pendingCount() {
        return -1L;
    }

    /**
     * Notification-based wait for newly-appended PENDING rows.
     *
     * <p>The worker poll loop calls this when its last claim returned zero rows. Implementations
     * that can detect appends in real time (in-memory: a {@link
     * java.util.concurrent.locks.Condition} signalled by {@link #append}; Redis: {@code XREAD
     * BLOCK}; PostgreSQL: {@code LISTEN/NOTIFY}; Kafka: long-poll on the outbox table CDC
     * stream) SHOULD override this to block until an append happens (or the timeout elapses).
     * The blocking primitive eliminates the worker's polling latency floor — the worker wakes
     * the instant a producer writes a row instead of after a {@code workerPollInterval} grid
     * tick.
     *
     * <p>Default implementation: returns {@code false} immediately. The worker then falls back
     * to its configured {@code workerPollInterval} sleep, preserving the original polling
     * shape for backends that cannot push-notify. This is correct but slower for the
     * idle-to-busy transition.
     *
     * @param maxWait the maximum time to block; must be positive. Implementations SHOULD return
     *                early on actual append, on caller-thread interrupt, or when {@code maxWait}
     *                elapses — whichever comes first.
     * @return {@code true} if a new PENDING row was observed during the wait (worker should
     *         claim immediately); {@code false} if the timeout elapsed without an append
     */
    default boolean awaitPendingOrTimeout(Duration maxWait) {
        // No-op default: backends that cannot push-notify fall back to polling. We sleep the
        // entire maxWait so the worker's idle loop preserves its original {@code
        // workerPollInterval} cadence — exactly the polling shape callers had before this
        // method existed. Implementations that CAN push-notify (in-memory: Condition signal;
        // JDBC: LISTEN/NOTIFY; Redis: XREAD BLOCK) override to wake the instant a producer
        // appends.
        java.util.Objects.requireNonNull(maxWait, "maxWait");
        if (maxWait.isNegative() || maxWait.isZero()) {
            return false;
        }
        try {
            Thread.sleep(maxWait.toMillis(), maxWait.toNanosPart() % 1_000_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * Atomically claim a batch of up to {@code max} rows that are {@code PENDING} and whose {@code
     * nextRetryAt} is either {@code null} or {@code <= now}. The claimed rows are returned in {@code
     * recordedAt} ascending order (recording order), and their persisted status is flipped to {@link
     * OutboxStatus#IN_FLIGHT} <em>before</em> this method returns.
     *
     * <p>Concurrent callers across threads (or across replicas, for the JDBC backend with {@code
     * SELECT FOR UPDATE SKIP LOCKED}) are guaranteed to receive disjoint batches: no row appears in
     * two batches simultaneously. This is the primary guard against double-delivery.
     *
     * <p>Idempotency note: rows that are already {@link OutboxStatus#IN_FLIGHT} are never included in
     * the returned batch, so a crash-then-restart scenario leaves previously claimed rows stuck in
     * {@code IN_FLIGHT} until a visibility-timeout sweep or manual intervention resets them.
     *
     * @param max maximum number of rows to claim; values {@code <= 0} return an empty list
     * @param now current wall-clock time used to filter rows whose {@code nextRetryAt} has elapsed
     * @return an unmodifiable, ordered list of claimed (IN_FLIGHT) records; never {@code null}
     */
    List<OutboxRecord> claimBatch(int max, Instant now);

    /**
     * Shard-aware overload of {@link #claimBatch(int, Instant)}. Multi-worker deployments call
     * this overload with a per-worker {@link OutboxClaimStrategy.ClaimContext} so the storage
     * returns only rows whose partition hashes to that worker — giving N workers a disjoint
     * claim space with zero cross-worker contention on the storage's CAS.
     *
     * <p>The default implementation falls back to {@link #claimBatch(int, Instant)}, ignoring
     * the shard context. Backends that support sharding override this method to honour the
     * context. The in-tree {@link InMemoryOutboxStorage} routes through its bound {@link
     * OutboxClaimStrategy}: with the default {@link
     * net.nexus_flow.core.outbox.claim.GlobalOrderedClaimStrategy} the shard predicate is
     * applied as a filter on the global index; with {@link
     * net.nexus_flow.core.outbox.claim.PartitionShardedClaimStrategy} the partition is the
     * primary index key so the iteration is O(K log(N/P)) per shard.
     *
     * <p>Adapters that have not yet implemented shard-aware claim inherit the legacy single-
     * worker behaviour — passing a multi-shard context against an unaware backend behaves
     * AS IF every shard owned every partition (correct but wasteful, as documented in
     * {@link OutboxClaimStrategy}).
     *
     * @param max maximum number of rows to claim; values {@code <= 0} return an empty list
     * @param now current wall-clock time used to filter rows whose {@code nextRetryAt} has elapsed
     * @param ctx shard / partition context; never {@code null};
     *            {@link OutboxClaimStrategy.ClaimContext#SINGLE_WORKER} is the default for
     *            non-sharded callers
     * @return an unmodifiable, ordered list of claimed (IN_FLIGHT) records; never {@code null}
     */
    default List<OutboxRecord> claimBatch(int max, Instant now, OutboxClaimStrategy.ClaimContext ctx) {
        java.util.Objects.requireNonNull(ctx, "ctx");
        return claimBatch(max, now);
    }

    /**
     * Final-success transition for a previously claimed row. Increments {@code attempts}, clears
     * {@code lastError}, sets {@code lastAttemptAt} to the storage clock, and flips status to {@link
     * OutboxStatus#PUBLISHED}.
     *
     * <p><strong>Idempotency:</strong> calling this method more than once for the same {@code id}
     * after the first call has already transitioned it to {@code PUBLISHED} is not idempotent — the
     * second call throws {@link IllegalOutboxTransitionException}. Callers should catch this
     * exception and treat it as a benign concurrent-completion signal (another worker delivered the
     * row first).
     *
     * <p><strong>Concurrency:</strong> concurrent calls for the <em>same</em> {@code id} are
     * serialised by the storage implementation (e.g. via {@code ConcurrentHashMap.compute} in the
     * in-memory backend, or a {@code UPDATE … WHERE status = 'IN_FLIGHT'} predicate in JDBC). Only
     * the first caller succeeds; subsequent callers receive {@link IllegalOutboxTransitionException}.
     *
     * @param id surrogate key of the row to transition; must not be {@code null}
     * @throws IllegalOutboxTransitionException if the row is unknown or its current status forbids
     *                                          the transition ({@code PUBLISHED} or {@code FAILED_TERMINAL})
     */
    void markPublished(OutboxId id);

    /**
     * Retryable-failure transition for a previously claimed row. Flattens {@code cause} (full stack
     * trace) into {@code lastError}, increments {@code attempts}, sets {@code lastAttemptAt} to the
     * storage clock, schedules {@code nextRetryAt} and flips status back to {@link
     * OutboxStatus#PENDING}.
     *
     * <p><strong>Idempotency:</strong> not idempotent — concurrent calls for the same {@code id} are
     * serialised and only one wins. The losing call throws {@link IllegalOutboxTransitionException}
     * if the row is no longer in a resolvable state.
     *
     * <p><strong>Concurrency:</strong> storage implementations must guarantee that the
     * read-modify-write is atomic (e.g. {@code ConcurrentHashMap.compute} or {@code UPDATE … WHERE
     * status = 'IN_FLIGHT'} in JDBC) to prevent a concurrent {@code markPublished} from clobbering
     * the failure record.
     *
     * @param id          surrogate key of the row to transition; must not be {@code null}
     * @param cause       the exception that caused the failure; its stack trace is flattened into {@code
     *     lastError}  ; must not be {@code null}
     * @param nextRetryAt earliest instant at which the row should be re-claimed; must not be {@code
     *     null}
     * @throws IllegalOutboxTransitionException if the row is unknown or its current status forbids
     *                                          the transition
     */
    void markFailed(OutboxId id, Throwable cause, Instant nextRetryAt);

    /**
     * Terminal-failure transition for a previously claimed row. Flattens {@code cause} into {@code
     * lastError}, increments {@code attempts}, sets {@code lastAttemptAt} and flips status to {@link
     * OutboxStatus#FAILED_TERMINAL}. The row will not appear in any subsequent {@link
     * #claimBatch(int, Instant)} call; only an {@code append} with the same {@link IdempotencyKey}
     * (manual replay) can resurrect it.
     *
     * <p><strong>Dead-letter handling:</strong> rows that reach {@code FAILED_TERMINAL} are not
     * automatically forwarded to a dead-letter queue. Application code that needs dead-letter
     * semantics should query for {@code FAILED_TERMINAL} rows and apply an external routing policy
     * (e.g. forwarding to a dedicated Kafka topic or alerting an operator). A future extension may
     * introduce a pluggable dead-letter listener callback.
     *
     * @param id    surrogate key of the row to transition; must not be {@code null}
     * @param cause the fatal exception; its stack trace is flattened into {@code lastError}; must not
     *              be {@code null}
     * @throws IllegalOutboxTransitionException if the row is unknown or its current status forbids
     *                                          the transition
     */
    void markFailedTerminal(OutboxId id, Throwable cause);

    /**
     * Non-destructive scan returning every PUBLISHED or PENDING row whose {@link
     * OutboxRecord#sequenceNo()} is strictly greater than {@code sinceSequence}, in ascending
     * sequence order, capped at {@code max} rows.
     *
     * <p><strong>What this is for.</strong> The ring transport's {@code RingOutboxBridge}
     * uses this method to replay events to a peer that has reconnected with a stale cursor:
     * the peer announces "I've seen up to sequence X"; the bridge calls
     * {@code findSinceSequence(X, batch)} and sends each row as an EVENT frame WITHOUT
     * mutating its status. Unlike {@link #claimBatch(int, Instant)} which transitions PENDING
     * rows to IN_FLIGHT, this method observes rows without side effect — it can therefore be
     * called repeatedly from any worker without affecting at-least-once delivery semantics
     * driven by other consumers.
     *
     * <p>Implementations MUST:
     * <ul>
     * <li>Return rows in ascending {@code sequenceNo} order.
     * <li>Filter out rows whose status is neither {@link OutboxStatus#PENDING} nor
     * {@link OutboxStatus#IN_FLIGHT} nor {@link OutboxStatus#PUBLISHED} — terminal
     * failures are not replay-eligible.
     * <li>Make no state transitions: a call to this method does not affect
     * {@code claimBatch} behaviour for any concurrent or subsequent caller.
     * </ul>
     *
     * <p><strong>Default implementation:</strong> logs a WARNING and returns an empty list.
     * In-memory and JDBC backends override with a real implementation. Adapter authors that
     * have not yet implemented this method ship without replay-on-reconnect support — the
     * ring bridge degrades to "fan-out works for newly-emitted events only".
     *
     * @param sinceSequence exclusive lower bound; rows with {@code sequenceNo > sinceSequence}
     *                      are returned
     * @param max           maximum number of rows; values {@code <= 0} return an empty list
     * @return an unmodifiable, ascending-by-sequenceNo list; never {@code null}
     */
    default List<OutboxRecord> findSinceSequence(long sinceSequence, int max) {
        LOG.log(Level.WARNING,
                () -> getClass().getName()
                        + " does not override findSinceSequence(); ring outbox replay-on-"
                        + "reconnect will be a no-op for this storage.");
        return List.of();
    }

    /**
     * Visibility-timeout sweep — recovers {@link OutboxStatus#IN_FLIGHT} rows whose claim is
     * older than {@code staleAfter}. Without a sweep, a worker crash between
     * {@link #claimBatch(int, Instant)} and {@link #markPublished(OutboxId)} would leave a
     * row stuck in {@code IN_FLIGHT} forever; this method is the automatic recovery hook.
     *
     * <p>Semantics: for every row whose status is {@link OutboxStatus#IN_FLIGHT} AND whose
     * claim happened more than {@code staleAfter} ago (relative to {@code now}),
     * transition the row back to {@link OutboxStatus#PENDING} WITHOUT incrementing the
     * attempt counter — a stuck claim is not a delivery failure, it's a worker liveness
     * failure. {@code nextRetryAt} is cleared so the row becomes eligible for re-claim
     * immediately.
     *
     * <p>The implementation MUST honor monotonic ordering: only rows whose original claim
     * is conclusively stale are recovered; rows claimed within {@code staleAfter} are
     * left alone so a slow but still-progressing worker does not have its work stolen.
     *
     * <p>Operator usage: schedule this sweep at half the maximum acceptable claim
     * duration (e.g., 30 s if the worst tolerable stuck-claim window is 1 minute). The
     * scheduled sweep is part of the framework's {@link OutboxWorker} lifecycle when
     * configured.
     *
     * <p><strong>Default implementation:</strong> logs a WARNING and returns {@code 0}.
     * In-memory and adapter-module backends override with a real implementation; the
     * default lets adapter authors ship a working storage before the visibility-timeout
     * hook is wired.
     *
     * @param staleAfter rows claimed longer ago than this are considered stale and
     *                   recovered; must be positive
     * @param now        wall-clock reference for the comparison
     * @return the number of rows recovered (flipped IN_FLIGHT → PENDING)
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code staleAfter} is negative or zero
     */
    default int sweepStaleClaims(Duration staleAfter, Instant now) {
        java.util.Objects.requireNonNull(staleAfter, "staleAfter");
        java.util.Objects.requireNonNull(now, "now");
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive: " + staleAfter);
        }
        LOG.log(Level.WARNING,
                () -> getClass().getName()
                        + " does not override sweepStaleClaims(); IN_FLIGHT rows can become"
                        + " stuck after a crash between claim and resolve, with no automatic"
                        + " recovery.");
        return 0;
    }

    /**
     * Releases an {@link OutboxStatus#IN_FLIGHT} row back to {@link OutboxStatus#PENDING} without
     * incrementing the attempt counter.
     *
     * <p>Called by {@link OutboxWorker} when a dispatch is interrupted by worker shutdown
     * (cooperative cancellation via {@link net.nexus_flow.core.runtime.CancellationToken}) so that
     * the row can be re-claimed on the next startup without burning an attempt. This is distinct from
     * a retryable failure ({@link #markFailed}) which does increment the counter.
     *
     * <p><strong>Default implementation:</strong> logs a WARNING and leaves the row in {@code
     * IN_FLIGHT}. In-memory and JDBC backends should override this to perform the true {@code
     * IN_FLIGHT → PENDING} transition. For the in-memory backend an un-resolved {@code IN_FLIGHT} row
     * is harmless (it disappears on JVM restart); for a JDBC backend a visibility- timeout sweep or
     * manual intervention recovers it. Overriding ensures zero attempt-counter inflation across
     * graceful shutdown/restart cycles.
     *
     * @param id surrogate key of the row to release; must not be {@code null}
     */
    default void releaseToReady(OutboxId id) {
        LOG.log(
                Level.WARNING,
                () -> getClass().getName()
                        + " does not override releaseToReady(); outbox row "
                        + id
                        + " left IN_FLIGHT — it will be recovered by a visibility-timeout sweep or on"
                        + " next startup");
    }

    /**
     * Deletes {@link OutboxStatus#PUBLISHED} rows whose {@code recordedAt} is strictly less than
     * {@code olderThan}. Returns the number of rows removed.
     *
     * <h4>What this is for</h4>
     *
     * The outbox is append-only by design — successful deliveries leave their {@code PUBLISHED} row
     * behind for audit, replay, and operator triage. Without a retention policy that table / map
     * grows unbounded. This method is the storage-level hook an operator (or a scheduled housekeeping
     * job) invokes to enforce a retention window.
     *
     * <h4>Safety guarantees</h4>
     *
     * <ul>
     * <li>ONLY rows in {@link OutboxStatus#PUBLISHED} are eligible. {@link OutboxStatus#PENDING}
     * rows (which represent undelivered work), {@link OutboxStatus#IN_FLIGHT} rows (currently
     * being processed), and {@link OutboxStatus#FAILED_TERMINAL} rows (which require manual
     * triage before they can be replayed via re-append) MUST NOT be deleted by this method.
     * Implementations enforce this — operators cannot accidentally lose unprocessed work or
     * silently discard rows that an operator was about to replay.
     * <li>The cutoff is exclusive ({@code recordedAt < olderThan}). A row recorded at exactly
     * {@code olderThan} survives the sweep.
     * <li>The method is a single atomic sweep from the caller's perspective: a row either survives
     * or is gone after return. Concurrent {@link #append(OutboxRecord)} of NEW rows during the
     * sweep is safe — new rows always have {@code recordedAt} &gt;= the sweep's start instant,
     * so they are not eligible.
     * </ul>
     *
     * <h4>Default implementation</h4>
     *
     * Logs a WARNING and returns {@code 0}. In-memory and adapter-module backends (JDBC, etc.)
     * override this with a real implementation. The fall-through default lets adapter authors ship a
     * working storage before they implement retention — the retention API is opt-in for the operator,
     * not a hard prerequisite for the storage to function.
     *
     * <h4>Recommended operator pattern</h4>
     *
     * Schedule a periodic job (e.g. once an hour) that calls {@code
     * storage.purgePublishedOlderThan(clock.instant().minus(retentionWindow))}. A typical retention
     * window is 7-30 days depending on audit / forensic requirements. The return value surfaces to
     * metrics ({@code outbox.purged.rows} per sweep) so dashboards can spot a runaway-growth
     * condition (purge returning zero while diagnostic observers report growth).
     *
     * @param olderThan exclusive cutoff; rows with {@code recordedAt} strictly less than this are
     *                  eligible for deletion; must not be {@code null}
     * @return the number of rows actually deleted; never negative
     * @throws NullPointerException if {@code olderThan} is {@code null}
     */
    default int purgePublishedOlderThan(Instant olderThan) {
        java.util.Objects.requireNonNull(olderThan, "olderThan");
        LOG.log(
                Level.WARNING,
                () -> getClass().getName()
                        + " does not override purgePublishedOlderThan(); the outbox will grow"
                        + " unbounded. Implement retention in your storage backend or wrap it in a"
                        + " periodic housekeeping job.");
        return 0;
    }
}

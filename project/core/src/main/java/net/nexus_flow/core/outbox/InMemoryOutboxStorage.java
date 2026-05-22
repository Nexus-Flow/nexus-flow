package net.nexus_flow.core.outbox;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * in-memory implementation of {@link OutboxStorage} for tests and single-node demos.
 *
 * <p><strong>Not production grade.</strong> The backing {@link ConcurrentHashMap} is JVM-local;
 * rows do not survive a restart. Use a {@code nexus-flow-jdbc} backend for any deployment that
 * needs at-least-once delivery across crashes.
 *
 * <p>Concurrency model: append, status transitions and claim are all serialized through the per-key
 * atomic ops of {@code ConcurrentHashMap} ({@code compute}, {@code computeIfAbsent}) plus a single
 * coarse-grained lock for {@link #claimBatch(int, Instant)}. The lock is intentional: claimBatch
 * must observe a consistent snapshot of eligible rows and flip them all atomically.
 *
 * <p>Claim-side ordering is delegated to a pluggable {@link OutboxClaimStrategy} bound at
 * construction time. The default {@link
 * net.nexus_flow.core.outbox.claim.GlobalOrderedClaimStrategy} maintains a single ordered
 * skiplist and serves single-worker deployments at O(K log N) per claim. Multi-worker
 * deployments wire {@link net.nexus_flow.core.outbox.claim.PartitionShardedClaimStrategy} so
 * each worker passes its {@link OutboxClaimStrategy.ClaimContext} via {@link #claimBatch(int,
 * Instant, OutboxClaimStrategy.ClaimContext)} for a disjoint, contention-free claim space.
 */
public final class InMemoryOutboxStorage implements OutboxStorage {

    private final ConcurrentHashMap<OutboxId, OutboxRecord>   outboxRecordIndex           = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IdempotencyKey, OutboxId> outboxIdempotencyKeyIndex   =
            new ConcurrentHashMap<>();
    /**
     * Per-row claim timestamp. Not part of {@link OutboxRecord} (which is frozen wire/persistence
     * shape) — this side map lets the visibility-timeout sweep identify rows whose claim is older
     * than the configured threshold without expanding the record's field surface.
     */
    private final ConcurrentHashMap<OutboxId, Instant>        outboxClaimTimestampIndex   = new ConcurrentHashMap<>();
    /**
     * Replay-side index of every non-{@link OutboxStatus#FAILED_TERMINAL} row ordered by
     * {@code (sequenceNo ASC, outboxId ASC)}. Reduces {@link #findSinceSequence} from
     * O(N log N) (full scan + sort) to O(K log N) (head-walk a skiplist starting at the
     * cursor). The ring transport's replay-on-reconnect path calls
     * {@code findSinceSequence} once per re-syncing peer; under churn this is hot enough that
     * the O(N log N) penalty becomes a deployment-wide stall in million-row outboxes.
     */
    private static final Comparator<OutboxRecord>             REPLAY_ORDER_COMPARATOR     =
            Comparator.comparingLong(OutboxRecord::sequenceNo)
                    .thenComparing(OutboxRecord::outboxId);
    private final ConcurrentSkipListSet<OutboxRecord>         outboxReplayBySequenceIndex =
            new ConcurrentSkipListSet<>(REPLAY_ORDER_COMPARATOR);
    /**
     * {@link java.util.concurrent.locks.ReentrantLock} (not intrinsic monitor) because the
     * claim/append paths are the hot contention surface — multiple aggregate writers append
     * concurrently, the worker drains the claim batch, and the visibility-timeout sweeper
     * walks the same lock. AQS-based parking is 5–20% faster than HotSpot heavyweight
     * monitor inflation in this contention shape (JDK 21+, post-biased-locking).
     */
    private final java.util.concurrent.locks.ReentrantLock    claimLock                   =
            new java.util.concurrent.locks.ReentrantLock();
    private final Clock                                       clock;
    private final OutboxClaimStrategy                         claimStrategy;

    /** Constructs an instance backed by {@link Clock#systemUTC()} and the global-ordered strategy. */
    public InMemoryOutboxStorage() {
        this(Clock.systemUTC(), new net.nexus_flow.core.outbox.claim.GlobalOrderedClaimStrategy());
    }

    /**
     * Constructs an instance with a custom clock and the global-ordered strategy.
     *
     * @param clock clock used to stamp {@code lastAttemptAt} on every status transition; must not be
     *              {@code null}
     */
    public InMemoryOutboxStorage(Clock clock) {
        this(clock, new net.nexus_flow.core.outbox.claim.GlobalOrderedClaimStrategy());
    }

    /**
     * Full-fidelity constructor — pick the claim strategy explicitly. Single-worker
     * deployments leave {@link net.nexus_flow.core.outbox.claim.GlobalOrderedClaimStrategy}
     * (the default); multi-worker deployments wire {@link
     * net.nexus_flow.core.outbox.claim.PartitionShardedClaimStrategy} so each worker passes
     * its {@link OutboxClaimStrategy.ClaimContext} via {@link #claimBatch(int, Instant,
     * OutboxClaimStrategy.ClaimContext)} for a disjoint claim space.
     *
     * @param clock         clock used to stamp {@code lastAttemptAt}; must not be {@code null}
     * @param claimStrategy claim strategy bound for this storage's lifetime; must not be
     *                      {@code null}
     */
    public InMemoryOutboxStorage(Clock clock, OutboxClaimStrategy claimStrategy) {
        this.clock         = Objects.requireNonNull(clock, "clock");
        this.claimStrategy = Objects.requireNonNull(claimStrategy, "claimStrategy");
    }

    /**
     * Lock + condition variable that wake the worker poll loop the instant a producer
     * appends a new row. Without this, an idle worker would have to sleep its full
     * {@code workerPollInterval} between checks even when a row arrives mid-tick — adding up
     * to a full poll interval of dead-time per appended row on the cold-to-hot transition.
     */
    private final java.util.concurrent.locks.ReentrantLock appendLock     =
            new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition     pendingArrived = appendLock.newCondition();

    /** @return the claim strategy bound at construction time. */
    public OutboxClaimStrategy claimStrategy() {
        return claimStrategy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>In-memory backend: blocks on a JDK {@link java.util.concurrent.locks.Condition} that
     * {@link #append} signals on every row insert. The worker wakes the instant a producer
     * appends — no polling-grid latency.
     */
    @Override
    public boolean awaitPendingOrTimeout(java.time.Duration maxWait) {
        java.util.Objects.requireNonNull(maxWait, "maxWait");
        if (maxWait.isNegative() || maxWait.isZero()) {
            return false;
        }
        // Fast path: if pending rows already exist, no need to wait.
        if (pendingCount() > 0L) {
            return true;
        }
        appendLock.lock();
        try {
            // Re-check under the lock to avoid missing a signal that arrived between the
            // unlocked check above and the await call below.
            if (pendingCount() > 0L) {
                return true;
            }
            long remaining = maxWait.toNanos();
            while (remaining > 0L) {
                try {
                    remaining = pendingArrived.awaitNanos(remaining);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (pendingCount() > 0L) {
                    return true;
                }
            }
            return false;
        } finally {
            appendLock.unlock();
        }
    }

    /** Notify any {@link #awaitPendingOrTimeout} waiter that a new row has been appended. */
    private void signalPendingArrived() {
        appendLock.lock();
        try {
            pendingArrived.signalAll();
        } finally {
            appendLock.unlock();
        }
    }

    @Override
    public void append(OutboxRecord outboxRecord) {
        Objects.requireNonNull(outboxRecord, "record");
        // FAILED_TERMINAL rows may be overwritten (manual replay path).
        // Any other status raises OutboxDuplicateKeyException.
        claimLock.lock();
        try {
            OutboxId existingId = outboxIdempotencyKeyIndex.get(outboxRecord.idempotencyKey());
            if (existingId != null) {
                OutboxRecord existing = outboxRecordIndex.get(existingId);
                if (existing != null && existing.status() != OutboxStatus.FAILED_TERMINAL) {
                    throw new OutboxDuplicateKeyException(outboxRecord.idempotencyKey());
                }
                if (existing != null) {
                    outboxRecordIndex.remove(existingId);
                    // FAILED_TERMINAL rows are not in the replay index — manual replay still
                    // emits onAppend on the fresh row below, so the index ends up coherent.
                }
            }
            outboxRecordIndex.put(outboxRecord.outboxId(), outboxRecord);
            outboxIdempotencyKeyIndex.put(outboxRecord.idempotencyKey(), outboxRecord.outboxId());
        } finally {
            claimLock.unlock();
        }
        claimStrategy.onAppend(outboxRecord);
        // The replay index tracks every row that is replay-eligible (anything not in
        // FAILED_TERMINAL). Fresh appends are PENDING, so they always enter the index.
        outboxReplayBySequenceIndex.add(outboxRecord);
        // Wake any worker blocked on awaitPendingOrTimeout — eliminates the
        // workerPollInterval latency floor on the idle-to-busy transition.
        signalPendingArrived();
    }

    @Override
    public List<OutboxRecord> claimBatch(int max, Instant now) {
        return claimBatch(max, now, OutboxClaimStrategy.ClaimContext.SINGLE_WORKER);
    }

    /**
     * Shard-aware claim. Multi-worker deployments wire a {@link
     * net.nexus_flow.core.outbox.claim.PartitionShardedClaimStrategy} at construction time and
     * pass a per-worker {@link OutboxClaimStrategy.ClaimContext} carrying
     * {@code (shardId, totalShards)} so the strategy returns only rows whose partition hashes
     * to that worker — giving N workers a disjoint claim space with zero cross-worker
     * contention.
     *
     * <p>Ordering contract (matches the JDBC adapter's canonical index plan):
     *
     * <pre>(priority DESC, partitionKey ASC, sequenceNo ASC, recordedAt ASC, outboxId ASC)</pre>
     *
     * <ul>
     * <li>{@code priority DESC} — higher priority values dispatch first across partitions.
     * <li>{@code partitionKey ASC} — groups rows from the same partition together so a
     * worker that takes the head of the batch will also see the next rows of THAT
     * partition contiguously, keeping per-partition FIFO end-to-end in single-worker
     * mode.
     * <li>{@code sequenceNo ASC} — within a partition, FIFO is strict (sequence numbers
     * are stamped by {@code Aggregate.recordEvent}, monotonic per aggregate).
     * <li>{@code recordedAt ASC} — tie-breaker for partitions with identical sequence
     * numbers (different aggregates).
     * <li>{@code outboxId ASC} — final tie-breaker for total determinism.
     * </ul>
     *
     * <p>JDBC adapter equivalent (Postgres):
     * <pre>{@code
     *   CREATE INDEX outbox_claim_idx
     *     ON outbox (status, next_retry_at,
     *                priority DESC, partition_key, sequence_no, recorded_at, outbox_id)
     *     WHERE status = 'PENDING';
     *   SELECT ... FROM outbox WHERE status='PENDING' AND (next_retry_at IS NULL OR
     *     next_retry_at <= now()) ORDER BY priority DESC, partition_key,
     *     sequence_no, recorded_at, outbox_id LIMIT batch FOR UPDATE SKIP LOCKED;
     * }</pre>
     *
     * <p>The in-memory path delegates ordering and iteration to the bound {@link
     * OutboxClaimStrategy}: default {@link
     * net.nexus_flow.core.outbox.claim.GlobalOrderedClaimStrategy} is O(K log N) per claim
     * (down from O(N log N) of the previous {@code Stream::sorted} implementation), and {@link
     * net.nexus_flow.core.outbox.claim.PartitionShardedClaimStrategy} amortises further across
     * workers.
     */
    @Override
    public List<OutboxRecord> claimBatch(int max, Instant now, OutboxClaimStrategy.ClaimContext ctx) {
        if (max <= 0) {
            return List.of();
        }
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(ctx, "ctx");
        claimLock.lock();
        try {
            return List.copyOf(
                               claimStrategy.claim(max, now, ctx, candidate -> {
                                   // Per-row atomic CAS: the strategy yields candidate references from
                                   // its index; the storage's computeIfPresent re-checks the row's
                                   // status under per-key atomicity. A concurrent markPublished /
                                   // markFailed / markFailedTerminal can transition the row between
                                   // the index read and this point — an unconditional outboxRecordIndex.put would
                                   // silently overwrite terminal states (PUBLISHED / FAILED_TERMINAL)
                                   // and markFailed-rescheduled PENDING rows back to IN_FLIGHT.
                                   AtomicReference<OutboxRecord> flippedRef = new AtomicReference<>();
                                   outboxRecordIndex.computeIfPresent(
                                                                      candidate.outboxId(),
                                                                      (k, current) -> {
                                                                          if (current.status() != OutboxStatus.PENDING) {
                                                                              return current;
                                                                          }
                                                                          if (current.nextRetryAt() != null && current.nextRetryAt()
                                                                                  .isAfter(now)) {
                                                                              return current;
                                                                          }
                                                                          OutboxRecord next = current.withStatus(OutboxStatus.IN_FLIGHT);
                                                                          flippedRef.set(next);
                                                                          return next;
                                                                      });
                                   OutboxRecord flipped = flippedRef.get();
                                   if (flipped != null) {
                                       outboxClaimTimestampIndex.put(flipped.outboxId(), now);
                                       return flipped;
                                   }
                                   return null;
                               }));
        } finally {
            claimLock.unlock();
        }
    }

    @Override
    public void markPublished(OutboxId id) {
        Objects.requireNonNull(id, "id");
        Instant                       attemptAt = clock.instant();
        AtomicReference<OutboxRecord> beforeRef = new AtomicReference<>();
        AtomicReference<OutboxRecord> afterRef  = new AtomicReference<>();
        outboxRecordIndex.compute(
                                  id,
                                  (k, current) -> {
                                      requireExists(k, current);
                                      requireResolvableFromInFlight(k, current);
                                      beforeRef.set(current);
                                      OutboxRecord next = current.asPublished(attemptAt);
                                      afterRef.set(next);
                                      return next;
                                  });
        outboxClaimTimestampIndex.remove(id);
        notifyTransition(beforeRef, afterRef);
    }

    @Override
    public void markFailed(OutboxId id, Throwable cause, Instant nextRetryAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(nextRetryAt, "nextRetryAt");
        String                        flattened = flatten(cause);
        Instant                       attemptAt = clock.instant();
        AtomicReference<OutboxRecord> beforeRef = new AtomicReference<>();
        AtomicReference<OutboxRecord> afterRef  = new AtomicReference<>();
        outboxRecordIndex.compute(
                                  id,
                                  (k, current) -> {
                                      requireExists(k, current);
                                      requireResolvableFromInFlight(k, current);
                                      beforeRef.set(current);
                                      OutboxRecord next = current.asRetrying(flattened, attemptAt, nextRetryAt);
                                      afterRef.set(next);
                                      return next;
                                  });
        outboxClaimTimestampIndex.remove(id);
        notifyTransition(beforeRef, afterRef);
    }

    @Override
    public void markFailedTerminal(OutboxId id, Throwable cause) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cause, "cause");
        String                        flattened = flatten(cause);
        Instant                       attemptAt = clock.instant();
        AtomicReference<OutboxRecord> beforeRef = new AtomicReference<>();
        AtomicReference<OutboxRecord> afterRef  = new AtomicReference<>();
        outboxRecordIndex.compute(
                                  id,
                                  (k, current) -> {
                                      requireExists(k, current);
                                      requireResolvableFromInFlight(k, current);
                                      beforeRef.set(current);
                                      OutboxRecord next = current.asFailedTerminal(flattened, attemptAt);
                                      afterRef.set(next);
                                      return next;
                                  });
        outboxClaimTimestampIndex.remove(id);
        // FAILED_TERMINAL rows are not replay-eligible — drop from the replay index now so
        // findSinceSequence's head-walk never encounters a terminal row.
        OutboxRecord beforeValue = beforeRef.get();
        if (beforeValue != null) {
            outboxReplayBySequenceIndex.remove(beforeValue);
        }
        notifyTransition(beforeRef, afterRef);
    }

    /**
     * Releases an {@link OutboxStatus#IN_FLIGHT} row back to {@link OutboxStatus#PENDING} without
     * incrementing the attempt counter. Rows already in a non-{@code IN_FLIGHT} state are left
     * unchanged.
     *
     * @param id surrogate key of the row to release; must not be {@code null}
     */
    @Override
    public void releaseToReady(OutboxId id) {
        Objects.requireNonNull(id, "id");
        AtomicReference<OutboxRecord> beforeRef = new AtomicReference<>();
        AtomicReference<OutboxRecord> afterRef  = new AtomicReference<>();
        outboxRecordIndex.computeIfPresent(
                                           id,
                                           (k, current) -> {
                                               if (current.status() != OutboxStatus.IN_FLIGHT)
                                                   return current;
                                               beforeRef.set(current);
                                               OutboxRecord next = current.asPending(null);
                                               afterRef.set(next);
                                               return next;
                                           });
        outboxClaimTimestampIndex.remove(id);
        notifyTransition(beforeRef, afterRef);
    }

    /**
     * Visibility-timeout sweep — flips {@link OutboxStatus#IN_FLIGHT} rows whose claim is
     * older than {@code staleAfter} back to {@link OutboxStatus#PENDING} without
     * incrementing the attempt counter. Uses the per-row claim timestamp from the side map
     * to identify stale claims.
     */
    @Override
    public int sweepStaleClaims(java.time.Duration staleAfter, Instant now) {
        Objects.requireNonNull(staleAfter, "staleAfter");
        Objects.requireNonNull(now, "now");
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive: " + staleAfter);
        }
        Instant cutoff    = now.minus(staleAfter);
        int     recovered = 0;
        for (var entry : outboxClaimTimestampIndex.entrySet()) {
            OutboxId id           = entry.getKey();
            Instant  claimInstant = entry.getValue();
            if (claimInstant.isAfter(cutoff)) {
                continue;
            }
            AtomicReference<OutboxRecord> beforeRef = new AtomicReference<>();
            AtomicReference<OutboxRecord> afterRef  = new AtomicReference<>();
            OutboxRecord                  updated   = outboxRecordIndex.computeIfPresent(id, (k, current) -> {
                                                        if (current.status() != OutboxStatus.IN_FLIGHT) {
                                                            return current;
                                                        }
                                                        beforeRef.set(current);
                                                        OutboxRecord next = current.asPending(null);
                                                        afterRef.set(next);
                                                        return next;
                                                    });
            if (updated != null && updated.status() == OutboxStatus.PENDING && afterRef.get() != null) {
                outboxClaimTimestampIndex.remove(id, claimInstant);
                notifyTransition(beforeRef, afterRef);
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * Atomically removes every {@link OutboxStatus#PUBLISHED} row whose {@code recordedAt} is
     * strictly less than {@code olderThan}. Returns the count.
     *
     * <p>Implementation walks the entry set, removes qualifying rows via {@code outboxRecordIndex.remove} and also
     * removes the corresponding {@code outboxIdempotencyKeyIndex} mapping so a future re-append of the same
     * idempotency key starts cleanly. The walk runs under {@code claimLock} so it serialises against
     * {@code append} and {@code claimBatch} — concurrent appends of new rows during the sweep are
     * safe because their {@code recordedAt} is &gt;= the sweep's observation window.
     */
    @Override
    public int purgePublishedOlderThan(Instant olderThan) {
        Objects.requireNonNull(olderThan, "olderThan");
        int removed = 0;
        claimLock.lock();
        try {
            var it = outboxRecordIndex.entrySet().iterator();
            while (it.hasNext()) {
                var          e = it.next();
                OutboxRecord r = e.getValue();
                if (r.status() == OutboxStatus.PUBLISHED && r.recordedAt().isBefore(olderThan)) {
                    it.remove();
                    outboxIdempotencyKeyIndex.remove(r.idempotencyKey());
                    claimStrategy.onRemove(r);
                    outboxReplayBySequenceIndex.remove(r);
                    removed++;
                }
            }
        } finally {
            claimLock.unlock();
        }
        return removed;
    }

    /**
     * Non-destructive replay scan. Returns every PENDING / IN_FLIGHT / PUBLISHED row whose
     * {@link OutboxRecord#sequenceNo()} is strictly greater than {@code sinceSequence}, in
     * ascending sequence order, capped at {@code max} rows. Terminal failures
     * ({@link OutboxStatus#FAILED_TERMINAL}) are excluded.
     *
     * <p>The implementation head-walks {@link #outboxReplayBySequenceIndex}, a skiplist ordered
     * by {@code (sequenceNo, outboxId)} maintained from {@link #append} / {@link
     * #markFailedTerminal} / {@link #purgePublishedOlderThan}. Each candidate is dereferenced
     * through {@link #outboxRecordIndex} so the returned rows carry the CURRENT field values
     * (the skiplist holds insert-time references whose fields drift as status transitions
     * happen; the primary {@code outboxRecordIndex} is always authoritative). Cost is
     * O(K log N) where K is the result size — down from O(N log N) of the previous full-scan
     * + sort implementation. The ring transport's replay-on-reconnect path calls this once per
     * re-syncing peer, so the saving compounds.
     */
    @Override
    public List<OutboxRecord> findSinceSequence(long sinceSequence, int max) {
        if (max <= 0) {
            return List.of();
        }
        List<OutboxRecord>     matches = new ArrayList<>(Math.min(max, 16));
        Iterator<OutboxRecord> iter    = outboxReplayBySequenceIndex.iterator();
        while (iter.hasNext() && matches.size() < max) {
            OutboxRecord candidate = iter.next();
            if (candidate.sequenceNo() <= sinceSequence) {
                continue;
            }
            // Dereference: the candidate is the insert-time reference; its `status` field
            // may be stale. The primary index is authoritative for current state.
            OutboxRecord current = outboxRecordIndex.get(candidate.outboxId());
            if (current == null) {
                // Purged between the skiplist read and here. Drop the stale reference.
                outboxReplayBySequenceIndex.remove(candidate);
                continue;
            }
            if (current.status() == OutboxStatus.FAILED_TERMINAL) {
                // A concurrent markFailedTerminal flipped the row but has not yet removed it
                // from the replay index (or this thread won the race). Drop and skip.
                outboxReplayBySequenceIndex.remove(candidate);
                continue;
            }
            matches.add(current);
        }
        return List.copyOf(matches);
    }

    /**
     * Returns the {@link OutboxRecord} for the given {@link OutboxId}, or {@code null} if absent.
     *
     * <p>Intended for tests and diagnostics only; not part of the {@link OutboxStorage} contract.
     *
     * @param id the surrogate key to look up
     * @return the record, or {@code null}
     */
    public OutboxRecord findById(OutboxId id) {
        return outboxRecordIndex.get(id);
    }

    /**
     * Returns an unmodifiable snapshot of all rows currently held in memory.
     *
     * <p>Intended for tests and diagnostics only; not part of the {@link OutboxStorage} contract.
     *
     * @return snapshot of all rows in unspecified order
     */
    public List<OutboxRecord> snapshot() {
        return List.copyOf(outboxRecordIndex.values());
    }

    /**
     * Returns the number of rows currently held in memory.
     *
     * <p>Intended for tests and diagnostics only.
     *
     * @return row count
     */
    public int size() {
        return outboxRecordIndex.size();
    }

    // --- helpers ------------------------------------------------------

    /**
     * Calls {@link OutboxClaimStrategy#onTransition(OutboxRecord, OutboxRecord)} only when the
     * compute block actually mutated the row — the {@code before} ref is set, the {@code after}
     * ref is set and the two are not the same instance. Avoids spurious notifications for
     * compute blocks that returned {@code current} unchanged (e.g. {@link #releaseToReady} when
     * the row was not IN_FLIGHT).
     */
    private void notifyTransition(
            AtomicReference<OutboxRecord> beforeRef, AtomicReference<OutboxRecord> afterRef) {
        OutboxRecord before = beforeRef.get();
        OutboxRecord after  = afterRef.get();
        if (before != null && after != null && before != after) {
            claimStrategy.onTransition(before, after);
        }
    }

    private static void requireExists(OutboxId id, OutboxRecord current) {
        if (current == null) {
            throw new IllegalOutboxTransitionException("outboxId " + id + " is not present in storage");
        }
    }

    private static void requireResolvableFromInFlight(OutboxId id, OutboxRecord current) {
        // Allow IN_FLIGHT (the normal path) and PENDING (e.g. a worker
        // that did not go through claimBatch — this path should not happen, but we
        // keep the contract permissive). Forbid terminal states.
        OutboxStatus s = current.status();
        if (s == OutboxStatus.PUBLISHED || s == OutboxStatus.FAILED_TERMINAL) {
            throw new IllegalOutboxTransitionException(
                    "outboxId " + id + " is in terminal status " + s + "; further transitions are forbidden");
        }
    }

    private static String flatten(Throwable t) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        return sw.toString();
    }

    /**
     * Linear scan of the local map filtered by {@link OutboxStatus#PENDING}. Acceptable for
     * the in-memory backend up to ~100k pending rows; production JDBC backends return a fast
     * approximation via {@code COUNT(*) WHERE status='PENDING'} or table statistics.
     */
    @Override
    public long pendingCount() {
        long count = 0L;
        for (OutboxRecord row : outboxRecordIndex.values()) {
            if (row.status() == OutboxStatus.PENDING) {
                count++;
            }
        }
        return count;
    }

    /**
     * Atomic batch append — acquires {@code claimLock} once and appends every row inside a
     * single critical section. On a duplicate-key collision mid-batch, rows appended earlier
     * stay (matches the JDBC default behaviour of {@code addBatch} without an explicit
     * transaction rollback); adapters that need cross-row atomicity wrap the whole batch in
     * their own transaction.
     */
    @Override
    public void appendBatch(List<OutboxRecord> records) {
        Objects.requireNonNull(records, "records");
        if (records.isEmpty()) {
            return;
        }
        claimLock.lock();
        try {
            for (OutboxRecord record : records) {
                append(Objects.requireNonNull(record, "record"));
            }
        } finally {
            claimLock.unlock();
        }
    }

    /**
     * Atomic batch publish-mark — acquires {@code claimLock} once and flips every row inside
     * a single critical section. Each id is validated per-row; an invalid transition surfaces
     * immediately and leaves the remaining ids unprocessed.
     */
    @Override
    public void markPublishedBatch(List<OutboxId> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return;
        }
        claimLock.lock();
        try {
            for (OutboxId id : ids) {
                markPublished(Objects.requireNonNull(id, "id"));
            }
        } finally {
            claimLock.unlock();
        }
    }
}

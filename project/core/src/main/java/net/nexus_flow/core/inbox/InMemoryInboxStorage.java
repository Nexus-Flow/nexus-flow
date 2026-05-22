package net.nexus_flow.core.inbox;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.nexus_flow.core.runtime.ids.MessageId;
import org.jspecify.annotations.Nullable;

/**
 * In-memory {@link InboxStorage} for tests and single-node demos.
 *
 * <h2>Not production-grade</h2>
 *
 * <p>The backing maps are JVM-local; no row survives a JVM restart and there is no cross-replica
 * coordination. Use a persistent backend (e.g. {@code nexus-flow-jdbc}) for any deployment that
 * requires at-least-once delivery guarantees across restarts or multiple replicas.
 *
 * <h2>Memory growth</h2>
 *
 * <p><strong>There is no eviction policy.</strong> Every successful {@link #claimIfNew} call
 * permanently adds a row to the in-memory maps. In long-running processes that receive many
 * distinct messages, memory will grow without bound. Production deployments must use a time-bounded
 * backend (e.g. Redis with a TTL, or a JDBC store with a scheduled purge) to prevent unbounded
 * memory growth. For tests, call {@link #clear()} between test cases or create a fresh instance per
 * test.
 *
 * <h2>Concurrency model</h2>
 *
 * <p>Deduplication correctness rests on two atomic operations of {@link ConcurrentHashMap}:
 *
 * <ol>
 * <li><strong>{@link ConcurrentHashMap#putIfAbsent} in {@link #claimIfNew}</strong> — exactly one
 * thread wins the insert for a given {@code (messageId, consumerId)} key. A non-null return
 * from {@code putIfAbsent} means another thread won the race; the losing thread returns
 * {@link InboxClaim.Duplicate}. The winning thread populates {@code inboxRecordIndex} <em>before</em>
 * returning {@link InboxClaim.Fresh} to its caller, so that subsequent {@link #markProcessed}
 * / {@link #markFailed} calls find the row immediately.
 * <p>A fast-path {@link ConcurrentHashMap#get} precedes {@code putIfAbsent} to avoid
 * allocating a new {@link InboxRecord} when the key is already known to exist; it does not
 * weaken the correctness guarantee.
 * <li><strong>{@link ConcurrentHashMap#compute} in {@code transition}</strong> — the status
 * update in {@code inboxRecordIndex} and the corresponding refresh of {@code inboxCompositeKeyIndex} are both performed
 * inside a single {@code compute} lambda, which is serialized per {@link InboxId} key. If the
 * lambda throws, ConcurrentHashMap discards the pending update and neither map is modified.
 * </ol>
 *
 * <p><strong>Ordering invariant between the two maps:</strong> {@code inboxCompositeKeyIndex} is written first
 * ({@code putIfAbsent}, then the {@code inboxCompositeKeyIndex.put} inside {@code compute}), followed by the {@code
 * inboxRecordIndex} entry. A concurrent reader of {@code inboxCompositeKeyIndex} that observes the new entry before
 * {@code inboxRecordIndex}
 * is fully populated will receive a {@link InboxClaim.Duplicate} result. This is safe because
 * {@link InboxClaim.Duplicate} recipients must never call {@link #markProcessed} or {@link
 * #markFailed}; only the {@link InboxClaim.Fresh} owner does, and it receives the result after both
 * maps have been populated.
 */
public final class InMemoryInboxStorage implements InboxStorage {

    private static final Logger LOG = System.getLogger(InMemoryInboxStorage.class.getName());

    /** Composite deduplication key. */
    private record Key(MessageId messageId, String consumerId) {
    }

    private final ConcurrentHashMap<Key, InboxRecord>     inboxCompositeKeyIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InboxId, InboxRecord> inboxRecordIndex       = new ConcurrentHashMap<>();

    @Override
    public InboxClaim claimIfNew(MessageId messageId, String consumerId, Instant now) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(consumerId, "consumerId");
        Objects.requireNonNull(now, "now");
        Key k = new Key(messageId, consumerId);

        // Fast path: skip record allocation when a prior claim is already visible.
        InboxRecord existing = inboxCompositeKeyIndex.get(k);
        if (existing != null) {
            if (LOG.isLoggable(Level.DEBUG)) {
                // Supplier defers parameter evaluation so the hot {@code claimDuplicate} path
                // (JMH-measured at ~11 ns) does not pay for the {@code Object[]} varargs
                // boxing on every claim — only the rare debug-enabled callers do.
                final InboxRecord existingFinal = existing;
                LOG.log(Level.DEBUG, () -> "Duplicate claim (fast path): messageId="
                        + messageId + " consumerId=" + consumerId + " status=" + existingFinal.status());
            }
            return new InboxClaim.Duplicate(existing.inboxId(), existing.status());
        }

        // Slow path: race to insert; putIfAbsent is the atomic exactly-once gate.
        InboxRecord fresh =
                new InboxRecord(
                        InboxId.next(), messageId, consumerId, InboxStatus.PROCESSING, now, now, null);
        InboxRecord prev  = inboxCompositeKeyIndex.putIfAbsent(k, fresh);
        if (prev != null) {
            if (LOG.isLoggable(Level.DEBUG)) {
                final InboxRecord prevFinal = prev;
                LOG.log(Level.DEBUG, () -> "Duplicate claim (slow path): messageId="
                        + messageId + " consumerId=" + consumerId + " status=" + prevFinal.status());
            }
            return new InboxClaim.Duplicate(prev.inboxId(), prev.status());
        }

        // We won the race. Populate inboxRecordIndex before returning Fresh so the caller
        // can immediately invoke markProcessed / markFailed.
        inboxRecordIndex.put(fresh.inboxId(), fresh);
        if (LOG.isLoggable(Level.DEBUG)) {
            LOG.log(Level.DEBUG, () -> "Fresh claim: messageId="
                    + messageId + " consumerId=" + consumerId + " inboxId=" + fresh.inboxId());
        }
        return new InboxClaim.Fresh(fresh.inboxId());
    }

    @Override
    public void markProcessed(InboxId id, Instant now) {
        transition(id, InboxStatus.PROCESSED, now, null);
    }

    @Override
    public void markFailed(InboxId id, Instant now) {
        transition(id, InboxStatus.FAILED, now, null);
    }

    @Override
    public void markFailed(InboxId id, @Nullable String error, Instant now) {
        transition(id, InboxStatus.FAILED, now, error);
    }

    /**
     * Atomically transitions the row identified by {@code id} to {@code target}, updating both {@code
     * inboxRecordIndex} and {@code inboxCompositeKeyIndex}.
     *
     * <p>The entire read-modify-write is serialized per {@link InboxId} key via {@link
     * ConcurrentHashMap#compute}. The {@code inboxCompositeKeyIndex} refresh inside the lambda runs while the {@code
     * inboxRecordIndex} segment lock is held; if the lambda throws, ConcurrentHashMap discards the pending update
     * and neither map is modified.
     *
     * <p>Transitioning a row that is already {@link InboxStatus#PROCESSED} throws {@link
     * IllegalInboxTransitionException} — this is the terminal guard that prevents double-processing
     * in retry storms. Transitioning {@link InboxStatus#FAILED} to any non-terminal state is
     * permitted to support retry flows.
     */
    private void transition(InboxId id, InboxStatus target, Instant now, @Nullable String error) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        inboxRecordIndex.compute(
                                 id,
                                 (k, current) -> {
                                     if (current == null) {
                                         throw new IllegalInboxTransitionException("inbox row not found: " + k);
                                     }
                                     if (current.status() == InboxStatus.PROCESSED) {
                                         throw new IllegalInboxTransitionException("inbox row " + k + " already PROCESSED");
                                     }
                                     InboxRecord next = current.withStatus(target, now, error);
                                     inboxCompositeKeyIndex.put(new Key(current.messageId(), current.consumerId()), next);
                                     return next;
                                 });
        if (LOG.isLoggable(Level.DEBUG)) {
            // Supplier defers formatting so the markProcessed / markFailed hot path does not
            // pay for the Object[] varargs boxing on every call.
            LOG.log(Level.DEBUG, () -> "Inbox row transitioned: inboxId=" + id + " target=" + target);
        }
    }

    @Override
    public boolean isProcessed(MessageId messageId, String consumerId) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(consumerId, "consumerId");
        InboxRecord r = inboxCompositeKeyIndex.get(new Key(messageId, consumerId));
        return r != null && r.status() == InboxStatus.PROCESSED;
    }

    /**
     * Returns the {@link InboxRecord} for the given {@link InboxId}, or {@code null} if not found.
     *
     * <p>Intended for tests and diagnostics only; not part of the {@link InboxStorage} contract.
     *
     * @param id the surrogate key to look up
     * @return the record, or {@code null}
     */
    public @Nullable InboxRecord findById(InboxId id) {
        return inboxRecordIndex.get(id);
    }

    /**
     * Returns the number of inbox rows currently held in memory.
     *
     * <p>Intended for tests and diagnostics only.
     *
     * @return row count
     */
    public int size() {
        return inboxRecordIndex.size();
    }

    /**
     * Removes all inbox rows from both internal maps.
     *
     * <p>Intended for test cleanup between test cases. Has no effect on running operations (any
     * {@link InboxClaim.Fresh} owners that call {@link #markProcessed} or {@link #markFailed} after
     * {@code clear()} will receive {@link IllegalInboxTransitionException}).
     */
    public void clear() {
        inboxRecordIndex.clear();
        inboxCompositeKeyIndex.clear();
    }

    /**
     * Linear-scan implementation. Iterates every row in {@code inboxRecordIndex}, removes those whose
     * {@code firstSeenAt} is strictly before {@code cutoff}, and atomically deletes the
     * paired {@code inboxCompositeKeyIndex} entry so a fresh {@code claimIfNew} for the same
     * {@code (messageId, consumerId)} pair after the purge sees a clean slate.
     *
     * <p>The in-memory scan is O(N) — acceptable for tests and single-node demos. JDBC
     * adapters back this with an indexed delete (the {@code first_seen_at} column SHOULD
     * carry an index when TTL purging is in scope).
     *
     * <p>The two-map removal is performed in this order: {@code inboxRecordIndex.remove} first, then
     * {@code inboxCompositeKeyIndex.remove(key, expectedValue)}. The {@code inboxCompositeKeyIndex} guard uses the
     * value-equality variant so a concurrent re-insert of the same key races safely — the
     * new entry stays, only the stale snapshot we observed is removed.
     *
     * @param cutoff wall-clock instant; rows whose {@code firstSeenAt} is strictly before
     *               this are removed; never {@code null}
     * @return the number of rows removed
     */
    @Override
    public long purgeBefore(java.time.Instant cutoff) {
        java.util.Objects.requireNonNull(cutoff, "cutoff");
        long removed = 0L;
        for (var entry : inboxRecordIndex.entrySet()) {
            InboxRecord row = entry.getValue();
            if (row.firstSeenAt().isBefore(cutoff)) {
                if (inboxRecordIndex.remove(entry.getKey(), row)) {
                    inboxCompositeKeyIndex.remove(new Key(row.messageId(), row.consumerId()), row);
                    removed++;
                }
            }
        }
        return removed;
    }
}

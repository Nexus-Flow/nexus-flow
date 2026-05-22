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
 * {@link InboxClaim.Duplicate}. The winning thread populates {@code byId} <em>before</em>
 * returning {@link InboxClaim.Fresh} to its caller, so that subsequent {@link #markProcessed}
 * / {@link #markFailed} calls find the row immediately.
 * <p>A fast-path {@link ConcurrentHashMap#get} precedes {@code putIfAbsent} to avoid
 * allocating a new {@link InboxRecord} when the key is already known to exist; it does not
 * weaken the correctness guarantee.
 * <li><strong>{@link ConcurrentHashMap#compute} in {@code transition}</strong> — the status
 * update in {@code byId} and the corresponding refresh of {@code byKey} are both performed
 * inside a single {@code compute} lambda, which is serialized per {@link InboxId} key. If the
 * lambda throws, ConcurrentHashMap discards the pending update and neither map is modified.
 * </ol>
 *
 * <p><strong>Ordering invariant between the two maps:</strong> {@code byKey} is written first
 * ({@code putIfAbsent}, then the {@code byKey.put} inside {@code compute}), followed by the {@code
 * byId} entry. A concurrent reader of {@code byKey} that observes the new entry before {@code byId}
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

    private final ConcurrentHashMap<Key, InboxRecord>     byKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InboxId, InboxRecord> byId  = new ConcurrentHashMap<>();

    @Override
    public InboxClaim claimIfNew(MessageId messageId, String consumerId, Instant now) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(consumerId, "consumerId");
        Objects.requireNonNull(now, "now");
        Key k = new Key(messageId, consumerId);

        // Fast path: skip record allocation when a prior claim is already visible.
        InboxRecord existing = byKey.get(k);
        if (existing != null) {
            LOG.log(
                    Level.DEBUG,
                    "Duplicate claim (fast path): messageId={0} consumerId={1} status={2}",
                    messageId,
                    consumerId,
                    existing.status());
            return new InboxClaim.Duplicate(existing.inboxId(), existing.status());
        }

        // Slow path: race to insert; putIfAbsent is the atomic exactly-once gate.
        InboxRecord fresh =
                new InboxRecord(
                        InboxId.next(), messageId, consumerId, InboxStatus.PROCESSING, now, now, null);
        InboxRecord prev  = byKey.putIfAbsent(k, fresh);
        if (prev != null) {
            LOG.log(
                    Level.DEBUG,
                    "Duplicate claim (slow path): messageId={0} consumerId={1} status={2}",
                    messageId,
                    consumerId,
                    prev.status());
            return new InboxClaim.Duplicate(prev.inboxId(), prev.status());
        }

        // We won the race. Populate byId before returning Fresh so the caller
        // can immediately invoke markProcessed / markFailed.
        byId.put(fresh.inboxId(), fresh);
        LOG.log(
                Level.DEBUG,
                "Fresh claim: messageId={0} consumerId={1} inboxId={2}",
                messageId,
                consumerId,
                fresh.inboxId());
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
     * byId} and {@code byKey}.
     *
     * <p>The entire read-modify-write is serialized per {@link InboxId} key via {@link
     * ConcurrentHashMap#compute}. The {@code byKey} refresh inside the lambda runs while the {@code
     * byId} segment lock is held; if the lambda throws, ConcurrentHashMap discards the pending update
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
        byId.compute(
                     id,
                     (k, current) -> {
                         if (current == null) {
                             throw new IllegalInboxTransitionException("inbox row not found: " + k);
                         }
                         if (current.status() == InboxStatus.PROCESSED) {
                             throw new IllegalInboxTransitionException("inbox row " + k + " already PROCESSED");
                         }
                         InboxRecord next = current.withStatus(target, now, error);
                         byKey.put(new Key(current.messageId(), current.consumerId()), next);
                         return next;
                     });
        LOG.log(Level.DEBUG, "Inbox row transitioned: inboxId={0} target={1}", id, target);
    }

    @Override
    public boolean isProcessed(MessageId messageId, String consumerId) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(consumerId, "consumerId");
        InboxRecord r = byKey.get(new Key(messageId, consumerId));
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
        return byId.get(id);
    }

    /**
     * Returns the number of inbox rows currently held in memory.
     *
     * <p>Intended for tests and diagnostics only.
     *
     * @return row count
     */
    public int size() {
        return byId.size();
    }

    /**
     * Removes all inbox rows from both internal maps.
     *
     * <p>Intended for test cleanup between test cases. Has no effect on running operations (any
     * {@link InboxClaim.Fresh} owners that call {@link #markProcessed} or {@link #markFailed} after
     * {@code clear()} will receive {@link IllegalInboxTransitionException}).
     */
    public void clear() {
        byId.clear();
        byKey.clear();
    }
}

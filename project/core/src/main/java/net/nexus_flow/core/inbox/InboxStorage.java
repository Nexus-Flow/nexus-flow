package net.nexus_flow.core.inbox;

import java.time.Instant;
import net.nexus_flow.core.runtime.ids.MessageId;
import org.jspecify.annotations.Nullable;

/**
 * SPI for inbox deduplication storage.
 *
 * <p>The deduplication key is the {@code (messageId, consumerId)} pair: a given message may be
 * processed by multiple consumer pipelines and each one deduplicates independently.
 *
 * <p>Implementations <strong>must be thread-safe</strong> and must guarantee that concurrent calls
 * to {@link #claimIfNew(MessageId, String, Instant)} with the same {@code (messageId, consumerId)}
 * pair produce exactly one {@link InboxClaim.Fresh} result. This is the core exactly-once
 * invariant. JDBC implementations should rely on a database {@code UNIQUE} constraint on {@code
 * (message_id, consumer_id)} and use {@code INSERT … ON CONFLICT DO NOTHING} (or equivalent). Redis
 * implementations should use {@code SET … NX} with an appropriate TTL.
 *
 * <p>This interface is intentionally unsealed. Production backends ({@code nexus-flow-jdbc}, {@code
 * nexus-flow-redis}, etc.) live in separate adapter modules and implement this interface without
 * being co-located in {@code core}.
 */
public interface InboxStorage {

    /**
     * Atomically reserves a delivery slot for the {@code (messageId, consumerId)} pair.
     *
     * <p>Exactly one call per unique pair returns {@link InboxClaim.Fresh}; all subsequent calls —
     * including concurrent ones from other threads or replicas — return {@link InboxClaim.Duplicate}.
     * This guarantee is the foundation of exactly-once processing semantics.
     *
     * @param messageId  the broker-assigned message identifier; must be stable across redeliveries of
     *                   the same logical message
     * @param consumerId logical name of the consumer pipeline (e.g. {@code "OrderService"}); scopes
     *                   deduplication so multiple pipelines can independently process the same message
     * @param now        current wall-clock time, recorded as {@link InboxRecord#firstSeenAt()} and {@link
     *                   InboxRecord#lastTransitionAt()} on initial insertion
     * @return {@link InboxClaim.Fresh} on first claim, {@link InboxClaim.Duplicate} on subsequent
     *         ones
     */
    InboxClaim claimIfNew(MessageId messageId, String consumerId, Instant now);

    /**
     * Checks whether the {@code (messageId, consumerId)} pair has been successfully processed.
     *
     * <p>This is a read-only, non-atomic query intended for monitoring, observability dashboards, and
     * idempotency guards in application code that cannot use the full claim-then-resolve protocol. It
     * does <em>not</em> replace {@link #claimIfNew} as the exactly-once gate: a {@code true} return
     * here only means the row existed and was in {@link InboxStatus#PROCESSED} at the time of the
     * query; a concurrent transition may have occurred immediately after.
     *
     * <p>The default implementation returns {@code false} unconditionally. Adapters that persist
     * inbox state should override this method to query the backing store.
     *
     * <p><strong>Dedup key semantics:</strong> the natural deduplication key is the {@code
     * (messageId, consumerId)} pair. A message may be processed independently by multiple consumer
     * pipelines; each pipeline's result is tracked separately.
     *
     * @param messageId  the broker-assigned message identifier to query
     * @param consumerId logical name of the consumer pipeline to query
     * @return {@code true} if a row for this pair exists and its status is {@link
     *         InboxStatus#PROCESSED}; {@code false} otherwise (not found, in-progress, or failed)
     */
    default boolean isProcessed(MessageId messageId, String consumerId) {
        return false;
    }

    /**
     * Transitions the inbox row identified by {@code id} to {@link InboxStatus#PROCESSED}.
     *
     * <p>Must be called by the owner of a {@link InboxClaim.Fresh} result after the message handler
     * completes successfully. Prefer {@link #markFailed(InboxId, String, Instant)} with a {@code
     * null} error if the error-string overload is not needed, but this method is provided for
     * convenience.
     *
     * @param id  surrogate key of the inbox row to update
     * @param now wall-clock time of the transition
     * @throws IllegalInboxTransitionException if the row does not exist or is already in the terminal
     *                                         {@link InboxStatus#PROCESSED} state
     */
    void markProcessed(InboxId id, Instant now);

    /**
     * Transitions the inbox row identified by {@code id} to {@link InboxStatus#FAILED}, without
     * recording a diagnostic message.
     *
     * <p>Prefer {@link #markFailed(InboxId, String, Instant)} to preserve the error cause for
     * observability and dead-letter inspection.
     *
     * @param id  surrogate key of the inbox row to update
     * @param now wall-clock time of the transition
     * @throws IllegalInboxTransitionException if the row does not exist or is already in the terminal
     *                                         {@link InboxStatus#PROCESSED} state
     */
    void markFailed(InboxId id, Instant now);

    /**
     * Transitions the inbox row identified by {@code id} to {@link InboxStatus#FAILED}, recording the
     * diagnostic {@code error} string for observability and dead-letter inspection.
     *
     * <p>The default implementation delegates to {@link #markFailed(InboxId, Instant)}, discarding
     * {@code error}. Adapters that persist error messages (e.g. JDBC-backed stores) should override
     * this method to store the cause in the {@link InboxRecord#lastError()} column.
     *
     * @param id    surrogate key of the inbox row to update
     * @param error diagnostic message (stack trace excerpt, exception class name, etc.); may be
     *              {@code null}
     * @param now   wall-clock time of the transition
     * @throws IllegalInboxTransitionException if the row does not exist or is already in the terminal
     *                                         {@link InboxStatus#PROCESSED} state
     */
    default void markFailed(InboxId id, @Nullable String error, Instant now) {
        markFailed(id, now);
    }

    /**
     * Bulk-delete every row whose {@link InboxRecord#firstSeenAt()} is strictly before
     * {@code cutoff}, returning the number of rows removed. Without periodic purging, an inbox
     * backed by a long-lived deployment grows without bound — exactly-once-effective semantics
     * only require the dedup horizon to be at least as long as the longest plausible message
     * redelivery window (broker retention + network outages + manual replay budget).
     *
     * <p>Implementations SHOULD purge ALL statuses, not just terminal ones:
     *
     * <ul>
     * <li>{@link InboxStatus#PROCESSED} rows older than {@code cutoff} are safe to remove
     * because their dedup window has expired — a hypothetical late redelivery now
     * re-enters processing, which is the operator-chosen trade-off when configuring a
     * finite TTL.
     * <li>{@link InboxStatus#FAILED} rows older than {@code cutoff} represent abandoned
     * attempts the operator has decided not to retry; deleting frees the dedup slot.
     * <li>{@link InboxStatus#PROCESSING} rows older than {@code cutoff} are stuck claims
     * (the worker died holding them); removing them ALSO releases the dedup slot so
     * a fresh claim path can re-process the message — the standard "abandoned-claim
     * recovery" pattern. Implementations that want to expose this transition
     * separately ship a tighter {@code recoverStaleClaims(...)} method.
     * </ul>
     *
     * <p><strong>Default implementation:</strong> throws {@link UnsupportedOperationException}.
     * Backends shipping before this method existed (legacy adapters) compile unchanged; any
     * deployment scheduling a purge worker against such a backend gets a fast-fail at the
     * first call instead of a silently-leaking inbox. Adapter modules add the appropriate
     * indexed delete ({@code DELETE FROM inbox WHERE first_seen_at < :cutoff} for JDBC,
     * {@code SCAN + DEL} for Redis).
     *
     * @param cutoff wall-clock instant; rows whose {@code firstSeenAt} is strictly before this
     *               are removed; never {@code null}
     * @return the number of rows removed (informational — for metrics / logging)
     * @throws NullPointerException if {@code cutoff} is {@code null}
     */
    default long purgeBefore(Instant cutoff) {
        throw new UnsupportedOperationException(
                getClass().getName()
                        + " does not implement purgeBefore(Instant); deployments that schedule"
                        + " a periodic TTL sweep must wire a backend that supports it (the"
                        + " in-memory backend does — adapters add the equivalent indexed"
                        + " delete).");
    }
}

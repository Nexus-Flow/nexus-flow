package net.nexus_flow.core.inbox;

import java.time.Instant;
import java.util.Objects;
import net.nexus_flow.core.runtime.ids.MessageId;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, persistent representation of a single delivery attempt.
 *
 * <p>The natural primary key of an inbox row is the {@code (messageId, consumerId)} pair: a single
 * message may be delivered to multiple consumer pipelines, and each pipeline dedupes independently.
 * {@link InboxId} is a surrogate key used for efficient status updates without re-reading the
 * natural key.
 *
 * <p>Immutability is deliberate: records held in memory are never mutated in place. State changes
 * produce a new instance via {@link #withStatus}, which keeps the concurrency model simple — map
 * entries can be replaced atomically with {@link java.util.concurrent.ConcurrentHashMap#compute} —
 * and provides a natural audit trail when rows are logged.
 */
public record InboxRecord(
                          InboxId inboxId,
                          MessageId messageId,
                          String consumerId,
                          InboxStatus status,
                          Instant firstSeenAt,
                          Instant lastTransitionAt,
                          @Nullable String lastError) {

    /** Validates all non-nullable components and that {@code consumerId} is not blank. */
    public InboxRecord {
        Objects.requireNonNull(inboxId, "inboxId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(consumerId, "consumerId");
        if (consumerId.isBlank()) {
            throw new IllegalArgumentException("consumerId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastTransitionAt, "lastTransitionAt");
    }

    /**
     * Returns a copy of this record with the given {@code newStatus}, {@code at} timestamp, and
     * optional {@code error} message.
     *
     * <p>{@link #firstSeenAt()} is preserved across all transitions; only {@link #lastTransitionAt()}
     * and {@link #lastError()} change. This invariant allows the full processing history to be
     * reconstructed from a sequence of records.
     *
     * @param newStatus the target status; the caller is responsible for verifying the transition is
     *                  permitted by the {@link InboxStatus} state machine
     * @param at        wall-clock time of the transition; stored for diagnostics, SLA monitoring, and
     *                  stale-claim detection
     * @param error     diagnostic message (e.g. exception class and message), or {@code null} for
     *                  successful transitions
     * @return a new {@code InboxRecord} reflecting the transition
     */
    public InboxRecord withStatus(InboxStatus newStatus, Instant at, @Nullable String error) {
        return new InboxRecord(inboxId, messageId, consumerId, newStatus, firstSeenAt, at, error);
    }
}

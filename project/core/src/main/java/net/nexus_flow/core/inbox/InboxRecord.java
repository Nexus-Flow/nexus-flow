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
 *
 * <h2>Why class, not record</h2>
 *
 * <p>Same reasoning as {@code OutboxRecord}: the 5 {@code Objects.requireNonNull} + 1
 * {@code isBlank} in the compact constructor run on every {@link #withStatus} transition even
 * though every field is sourced from {@code this} (already validated). The class exposes a
 * package-private {@link #unchecked} factory that skips validation for self-derived
 * transitions; the public constructor still validates for external callers.
 */
public final class InboxRecord {

    private final InboxId          inboxId;
    private final MessageId        messageId;
    private final String           consumerId;
    private final InboxStatus      status;
    private final Instant          firstSeenAt;
    private final Instant          lastTransitionAt;
    private final @Nullable String lastError;

    /** Validates all non-nullable components and that {@code consumerId} is not blank. */
    public InboxRecord(
            InboxId inboxId,
            MessageId messageId,
            String consumerId,
            InboxStatus status,
            Instant firstSeenAt,
            Instant lastTransitionAt,
            @Nullable String lastError) {
        this.inboxId    = Objects.requireNonNull(inboxId, "inboxId");
        this.messageId  = Objects.requireNonNull(messageId, "messageId");
        this.consumerId = Objects.requireNonNull(consumerId, "consumerId");
        if (consumerId.isBlank()) {
            throw new IllegalArgumentException("consumerId must not be blank");
        }
        this.status           = Objects.requireNonNull(status, "status");
        this.firstSeenAt      = Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        this.lastTransitionAt = Objects.requireNonNull(lastTransitionAt, "lastTransitionAt");
        this.lastError        = lastError;
    }

    /**
     * Private skeleton constructor — assigns fields without validation. Used by
     * {@link #unchecked} for the self-derived transition path.
     */
    private InboxRecord(
            InboxId inboxId,
            MessageId messageId,
            String consumerId,
            InboxStatus status,
            Instant firstSeenAt,
            Instant lastTransitionAt,
            @Nullable String lastError,
            @SuppressWarnings("unused") boolean uncheckedMarker) {
        this.inboxId          = inboxId;
        this.messageId        = messageId;
        this.consumerId       = consumerId;
        this.status           = status;
        this.firstSeenAt      = firstSeenAt;
        this.lastTransitionAt = lastTransitionAt;
        this.lastError        = lastError;
    }

    /**
     * Package-private fast-path factory — bypasses validation. Safe when every argument is
     * sourced from another already-validated {@link InboxRecord} (the {@link #withStatus}
     * transition path).
     */
    static InboxRecord unchecked(
            InboxId inboxId,
            MessageId messageId,
            String consumerId,
            InboxStatus status,
            Instant firstSeenAt,
            Instant lastTransitionAt,
            @Nullable String lastError) {
        return new InboxRecord(inboxId, messageId, consumerId, status,
                firstSeenAt, lastTransitionAt, lastError, true);
    }

    public InboxId inboxId() {
        return inboxId;
    }

    public MessageId messageId() {
        return messageId;
    }

    public String consumerId() {
        return consumerId;
    }

    public InboxStatus status() {
        return status;
    }

    public Instant firstSeenAt() {
        return firstSeenAt;
    }

    public Instant lastTransitionAt() {
        return lastTransitionAt;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InboxRecord other)) {
            return false;
        }
        return Objects.equals(inboxId, other.inboxId) && Objects.equals(messageId, other.messageId) && Objects.equals(consumerId,
                                                                                                                      other.consumerId) && status == other.status && Objects
                                                                                                                              .equals(firstSeenAt,
                                                                                                                                      other.firstSeenAt) && Objects
                                                                                                                                              .equals(lastTransitionAt,
                                                                                                                                                      other.lastTransitionAt) && Objects
                                                                                                                                                              .equals(lastError,
                                                                                                                                                                      other.lastError);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inboxId, messageId, consumerId, status,
                            firstSeenAt, lastTransitionAt, lastError);
    }

    @Override
    public String toString() {
        return "InboxRecord["
                + "inboxId=" + inboxId
                + ", messageId=" + messageId
                + ", consumerId=" + consumerId
                + ", status=" + status
                + ", firstSeenAt=" + firstSeenAt
                + ", lastTransitionAt=" + lastTransitionAt
                + ", lastError=" + lastError
                + ']';
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
        Objects.requireNonNull(newStatus, "newStatus");
        Objects.requireNonNull(at, "at");
        return unchecked(inboxId, messageId, consumerId, newStatus, firstSeenAt, at, error);
    }
}

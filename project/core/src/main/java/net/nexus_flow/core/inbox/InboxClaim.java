package net.nexus_flow.core.inbox;

/**
 * Sealed result type of {@link InboxStorage#claimIfNew(net.nexus_flow.core.runtime.ids.MessageId,
 * String, java.time.Instant)}.
 *
 * <p>Pattern-match on the two variants to implement the at-least-once → exactly-once contract:
 *
 * <pre>{@code
 * switch (inbox.claimIfNew(messageId, consumerId, Instant.now())) {
 * case InboxClaim.Fresh(var id) -> {
 * try {
 * handler.handle(message);
 * inbox.markProcessed(id, Instant.now());
 * } catch (Exception e) {
 * inbox.markFailed(id, e.getMessage(), Instant.now());
 * }
 * }
 * case InboxClaim.Duplicate(var id, var status) -> {
 * // PROCESSED → silently skip (work already done)
 * // FAILED → apply retry / dead-letter policy
 * // PROCESSING → another worker is active; back off or log
 * }
 * }
 * }</pre>
 *
 * <ul>
 * <li>{@link Fresh} — the {@code (messageId, consumerId)} pair was unseen and the claim was
 * atomically persisted with status {@link InboxStatus#PROCESSING}. The owner of this result
 * <strong>must</strong> follow with either {@link InboxStorage#markProcessed} or {@link
 * InboxStorage#markFailed}. Failing to do so leaves the row in {@code PROCESSING}
 * indefinitely, which stale-claim detection policies will eventually flag.
 * <li>{@link Duplicate} — the pair already existed. The embedded {@link InboxStatus} carries the
 * state of the prior attempt: {@link InboxStatus#PROCESSED} means the work is done; {@link
 * InboxStatus#FAILED} means a prior attempt failed and a retry decision is needed; {@link
 * InboxStatus#PROCESSING} means another worker currently holds the claim and the caller
 * should back off.
 * </ul>
 */
public sealed interface InboxClaim {

    /**
     * The storage-level surrogate key of the inbox row associated with this claim result.
     *
     * <p>Owners of a {@link Fresh} result use this ID to call {@link InboxStorage#markProcessed} or
     * {@link InboxStorage#markFailed}. Recipients of a {@link Duplicate} receive the ID for
     * diagnostic purposes only and must not call transition methods with it.
     *
     * @return the non-null {@link InboxId} of the inbox row
     */
    InboxId id();

    /**
     * A new, previously unseen {@code (messageId, consumerId)} pair.
     *
     * <p>The row was atomically inserted with status {@link InboxStatus#PROCESSING}. The caller owns
     * the processing obligation and must eventually call {@link InboxStorage#markProcessed} or {@link
     * InboxStorage#markFailed}.
     *
     * @param id surrogate key of the newly created inbox row
     */
    record Fresh(InboxId id) implements InboxClaim {
    }

    /**
     * A {@code (messageId, consumerId)} pair that was already present in storage.
     *
     * <p>The {@link #status()} reflects the last known state of the prior processing attempt. The
     * recipient must not call {@link InboxStorage#markProcessed} or {@link InboxStorage#markFailed}
     * with this ID; those methods are reserved for the {@link Fresh} owner.
     *
     * @param id     surrogate key of the existing inbox row
     * @param status last recorded status of the prior attempt
     */
    record Duplicate(InboxId id, InboxStatus status) implements InboxClaim {
    }
}

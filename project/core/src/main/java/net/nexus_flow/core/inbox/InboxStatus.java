package net.nexus_flow.core.inbox;

/**
 * Life-cycle state of an {@link InboxRecord}.
 *
 * <p>Valid transitions:
 *
 * <pre>{@code
 * PROCESSING ►► PROCESSED (handler completed successfully)
 * PROCESSING ►► FAILED (handler threw an exception)
 * FAILED ►► PROCESSED (retry succeeded)
 * FAILED ►► FAILED (retry failed again)
 * }</pre>
 *
 * <p>{@link #PROCESSED} is a terminal state: no further transitions are permitted. This guard
 * prevents double-processing in retry storms, where a slow consumer and a redelivery may race to
 * mark the same record as processed.
 */
public enum InboxStatus {

    /**
     * A {@link InboxClaim.Fresh} claim was issued and the message handler is executing — or the JVM
     * crashed mid-flight.
     *
     * <p>A row that remains in this state beyond the expected handler timeout signals an orphaned
     * claim. Retry policies should treat a stale {@code PROCESSING} row as a crash indicator and
     * apply a visibility-timeout strategy before re-claiming.
     */
    PROCESSING,

    /**
     * The message handler completed successfully. Terminal: the inbox row will not be transitioned
     * further. Duplicate deliveries for this {@code (messageId, consumerId)} pair are silently
     * dropped.
     */
    PROCESSED,

    /**
     * The message handler threw an exception. The row is eligible for retry according to the
     * configured retry policy. After all retries are exhausted, the dead-letter policy should
     * quarantine the record for manual inspection.
     */
    FAILED
}

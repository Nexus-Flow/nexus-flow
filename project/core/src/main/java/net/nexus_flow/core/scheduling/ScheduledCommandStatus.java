package net.nexus_flow.core.scheduling;

/**
 * Lifecycle states of a {@link ScheduledCommandRecord} row.
 *
 * <p>Allowed transitions:
 *
 * <pre>
 * PENDING ──claim+dispatch──&gt; DISPATCHED (terminal, happy path)
 * PENDING ──retryable error──&gt; PENDING (rescheduled with new fireAt)
 * PENDING ──terminal error──&gt; FAILED_TERMINAL (terminal)
 * </pre>
 *
 * <p>Only rows in {@code PENDING} are returned by {@link ScheduledCommandStorage#claimDue(int,
 * java.time.Instant)}. Terminal states ({@code DISPATCHED}, {@code FAILED_TERMINAL}) are left in
 * storage for audit purposes.
 *
 * <p><strong>Thread-safety:</strong> this enum is immutable and safe for concurrent access.
 */
public enum ScheduledCommandStatus {
    /** Awaiting its {@code fireAt} window or in-flight after a transient failure. */
    PENDING,
    /** Successfully dispatched through the command bus; no further action needed. */
    DISPATCHED,
    /** Permanently failed after exhausting all retry attempts — left for manual inspection. */
    FAILED_TERMINAL
}

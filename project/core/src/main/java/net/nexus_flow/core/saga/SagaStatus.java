package net.nexus_flow.core.saga;

/**
 * Lifecycle states of a saga instance.
 *
 * <p><strong>State machine:</strong> A saga progresses through these states:
 *
 * <pre>
 * RUNNING ──► COMPLETED (Saga.handle returned Complete)
 * ──► COMPENSATING (Saga.handle returned Compensate; immediately -> COMPENSATED)
 * ──► FAILED_TERMINAL (Saga.handle returned Fail)
 * </pre>
 *
 * <p><strong>Terminal states:</strong> {@link #COMPLETED}, {@link #COMPENSATED}, and {@link
 * #FAILED_TERMINAL} are terminal — no further events alter the saga's state. Once a saga reaches a
 * terminal state, all subsequent envelopes are ignored (checkpoint-based idempotency ensures this
 * is safe).
 */
public enum SagaStatus {
    /** Saga is alive and consuming events from the event store. */
    RUNNING,
    /** Saga finished successfully. Terminal — no further transitions. */
    COMPLETED,
    /** Saga initiated compensation (reserved; not used in single-shot compensation model). */
    COMPENSATING,
    /** Saga finished its compensation round. Terminal — no further transitions. */
    COMPENSATED,
    /**
     * Saga failed unrecoverably (e.g., handle() threw an exception, or compensation failed). Terminal
     * — no further transitions.
     */
    FAILED_TERMINAL;

    /** {@code true} if this status admits no further transitions. */
    public boolean isTerminal() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED_TERMINAL;
    }
}

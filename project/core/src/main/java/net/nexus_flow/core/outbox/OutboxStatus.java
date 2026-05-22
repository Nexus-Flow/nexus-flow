package net.nexus_flow.core.outbox;

/**
 * lifecycle state of a row in {@link OutboxStorage}.
 *
 * <p>Legal transitions are enforced by the storage implementation:
 *
 * <pre>
 * PENDING ──claimBatch──▶ IN_FLIGHT
 * IN_FLIGHT ──markPublished──▶ PUBLISHED (terminal-success)
 * IN_FLIGHT ──markFailed──▶ PENDING (with retry)
 * IN_FLIGHT ──markFailedTerminal──▶ FAILED_TERMINAL (terminal-failure)
 * FAILED_TERMINAL ──append──▶ PENDING (manual replay)
 * </pre>
 *
 * <p>Any other transition raises {@link IllegalOutboxTransitionException}.
 */
public enum OutboxStatus {
    /** Awaiting drain — eligible for {@link OutboxStorage#claimBatch}. */
    PENDING,

    /** Currently held by a worker —publishes it then resolves. */
    IN_FLIGHT,

    /** Successfully drained to the online bus — terminal success. */
    PUBLISHED,

    /** Drain abandoned — terminal failure; only manual replay (re-append) can resurrect. */
    FAILED_TERMINAL
}

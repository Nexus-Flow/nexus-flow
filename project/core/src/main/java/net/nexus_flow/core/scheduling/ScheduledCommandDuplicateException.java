package net.nexus_flow.core.scheduling;

import java.io.Serial;

/**
 * Thrown by {@link ScheduledCommandStorage#schedule(ScheduledCommandRecord)} when a row with the
 * given {@link ScheduledCommandId} already exists in a non-terminal status.
 *
 * <p>Mirrors {@code OutboxDuplicateKeyException} from the outbox package. The runtime treats this
 * as a programming-error or replay signal, not as a recoverable failure.
 *
 * <p><strong>Idempotent scheduling:</strong> callers that assign ids deterministically (e.g.
 * derived from a domain event id) can catch this exception to implement idempotent scheduling — the
 * command will still be dispatched by the existing row.
 *
 * <p><strong>Thread-safety:</strong> this exception is immutable and safe for concurrent access.
 */
public final class ScheduledCommandDuplicateException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    // ScheduledCommandId is a domain value object that is not Serializable;
    // marking the field transient silences the [serial] lint without changing
    // observable behaviour — exceptions are produced and consumed in-process.
    private final transient ScheduledCommandId id;

    /**
     * Construct an exception for a duplicate schedule attempt.
     *
     * @param id the id of the duplicate row; must not be {@code null}
     */
    public ScheduledCommandDuplicateException(ScheduledCommandId id) {
        super("scheduled command already exists: " + id);
        this.id = id;
    }

    /**
     * Return the id of the duplicate row.
     *
     * @return the id that was already scheduled
     */
    public ScheduledCommandId id() {
        return id;
    }
}

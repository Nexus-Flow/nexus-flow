package net.nexus_flow.core.outbox;

import java.io.Serial;

/**
 * raised when callers attempt a state transition that the lifecycle in {@link OutboxStatus} forbids
 * (e.g. {@code markPublished} on a row already in {@code FAILED_TERMINAL}, or {@code markFailed} on
 * a row that no longer exists).
 */
public final class IllegalOutboxTransitionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a message describing the illegal transition.
     *
     * @param message human-readable description of the violation, including the {@link OutboxId} and
     *                the current {@link OutboxStatus}
     */
    public IllegalOutboxTransitionException(String message) {
        super(message);
    }
}

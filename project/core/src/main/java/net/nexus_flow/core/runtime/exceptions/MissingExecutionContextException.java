package net.nexus_flow.core.runtime.exceptions;

import java.io.Serial;

/**
 * Thrown by {@code FlowScope.requireCurrent()} (and any other infrastructure code that mandates an
 * {@code ExecutionContext}) when called outside any {@code runWithContext} / {@code getWithContext}
 * dynamic extent.
 *
 * <p>Extends {@link IllegalStateException} so the idiomatic catch still works; callers that need to
 * distinguish "no context" from other illegal states can target this subclass.
 */
public final class MissingExecutionContextException extends IllegalStateException {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates the default missing-context exception with a stable message. */
    public MissingExecutionContextException() {
        super("No ExecutionContext bound on the current thread");
    }

    /**
     * Creates a missing-context exception with a custom message.
     *
     * @param message human-readable description; never {@code null}
     */
    public MissingExecutionContextException(String message) {
        super(message);
    }
}

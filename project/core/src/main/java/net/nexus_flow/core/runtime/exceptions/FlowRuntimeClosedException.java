package net.nexus_flow.core.runtime.exceptions;

import java.io.Serial;

/**
 * Thrown when any operation is invoked on a {@code FlowRuntime} that has already been closed.
 *
 * <p>Extends {@link IllegalStateException} so existing callers catching the idiomatic Java type
 * continue to work, while frameworks and observability integrations can target this specific type
 * to distinguish "use after close" from other illegal states.
 */
public final class FlowRuntimeClosedException extends IllegalStateException {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates the default closed-runtime exception with a stable message. */
    public FlowRuntimeClosedException() {
        super("FlowRuntime has been closed; obtain a fresh instance via FlowRuntime.builder().build()");
    }

    /**
     * Creates a closed-runtime exception with a custom message — useful for sub-component messages
     * (e.g., "outbox of the closed runtime cannot accept new entries").
     *
     * @param message human-readable description; never {@code null}
     */
    public FlowRuntimeClosedException(String message) {
        super(message);
    }
}

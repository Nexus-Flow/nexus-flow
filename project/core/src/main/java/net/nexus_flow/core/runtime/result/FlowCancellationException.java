package net.nexus_flow.core.runtime.result;

import java.io.Serial;

/**
 * Thrown when a dispatch is observed to be canceled at a safe point.
 *
 * <p>Always represents a <em>technical</em> cancellation, never a domain outcome.
 */
public final class FlowCancellationException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates a cancellation exception with the default message {@code "Dispatch was cancelled"}. */
    public FlowCancellationException() {
        super("Dispatch was cancelled");
    }

    /**
     * Creates a cancellation exception with a custom message.
     *
     * @param message human-readable description of why the dispatch was cancelled; may include the
     *                name of the gate or scope that raised cancellation
     */
    public FlowCancellationException(String message) {
        super(message);
    }
}

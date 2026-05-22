package net.nexus_flow.core.cqrs.query.exceptions;

import java.io.Serial;

/** Wraps failures that occur while executing a query handler. */
public final class QueryHandlerExecutionError extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a wrapper for a handler execution failure.
     *
     * @param cause underlying failure
     */
    public QueryHandlerExecutionError(Throwable cause) {
        super(cause);
    }
}

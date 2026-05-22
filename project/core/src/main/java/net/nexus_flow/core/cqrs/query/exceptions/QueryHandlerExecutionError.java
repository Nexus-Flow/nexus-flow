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
        // Stack-traceless wrapper — the cause carries the real handler-execution stack trace.
        // The wrapper's trace would only point to the query bus boundary. Suppression chain
        // stays active. Saves ~200 ns per query handler failure.
        // Preserve the {@code super(Throwable)} message contract — that constructor stamps
        // {@code cause.toString()} as the message, so {@link #getMessage()} keeps returning a
        // non-null value (tests pin this contract).
        super(
              cause == null ? null : cause.toString(),
              cause,
              /* enableSuppression= */ true,
              /* writableStackTrace= */ false);
    }
}

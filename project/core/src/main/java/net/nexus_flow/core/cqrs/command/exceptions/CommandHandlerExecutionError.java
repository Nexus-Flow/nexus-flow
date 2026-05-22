package net.nexus_flow.core.cqrs.command.exceptions;

import java.io.Serial;

/**
 * Wraps a failure observed while a command handler is being executed.
 *
 * <p>This is a framework-level dispatch boundary wrapper, not a modelled domain outcome — it
 * conveys "the command bus tried to run a handler and the handler (or the dispatch pipeline
 * around it) raised an exception". The original cause is preserved through {@link
 * Throwable#getCause()} so observability and recovery code can inspect the underlying failure.
 *
 * <p>Stack-traceless: the cause already carries the real handler-execution stack trace; this
 * wrapper's own trace would only point to the bus boundary and adds no diagnostic value.
 * Suppression chain remains active so {@code ThrowableUtils.withSuppressed} can attach losing
 * causes.
 */
public final class CommandHandlerExecutionError extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a command execution error for the supplied handler failure.
     *
     * @param cause original exception thrown by the handler or dispatch pipeline; must not be
     *              {@code null}
     */
    public CommandHandlerExecutionError(Throwable cause) {
        super(
              cause == null ? null : cause.toString(),
              cause,
              /* enableSuppression= */ true,
              /* writableStackTrace= */ false);
    }
}

package net.nexus_flow.core.cqrs.command.exceptions;

import java.io.Serial;
import java.util.Objects;
import net.nexus_flow.core.ddd.exceptions.DomainError;

/**
 * Wraps an exception thrown while a command handler is being executed.
 *
 * <p>The original cause is preserved so callers and observability integrations can inspect the
 * handler failure without losing the command-layer error code.
 */
public final class CommandHandlerExecutionError extends DomainError {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a command execution error for the supplied handler failure.
     *
     * @param cause original exception thrown by the handler or dispatch pipeline
     */
    public CommandHandlerExecutionError(Throwable cause) {
        super("command_execution_error", Objects.requireNonNull(cause, "cause").getMessage(), cause);
    }
}

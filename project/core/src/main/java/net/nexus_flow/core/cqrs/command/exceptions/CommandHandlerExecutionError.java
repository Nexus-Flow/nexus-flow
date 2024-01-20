package net.nexus_flow.core.cqrs.command.exceptions;

import net.nexus_flow.core.ddd.exceptions.DomainError;

import java.io.Serial;

public final class CommandHandlerExecutionError extends DomainError {
    @Serial
    private static final long serialVersionUID = 1L;

    public CommandHandlerExecutionError(Throwable cause) {
        super("command_execution_error", cause.getMessage());
    }
}

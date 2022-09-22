package com.nexus_flow.core.cqrs.domain.command.exceptions;

public final class CommandHandlerExecutionError extends RuntimeException {
    public CommandHandlerExecutionError(Throwable cause) {
        super(cause);
    }
}

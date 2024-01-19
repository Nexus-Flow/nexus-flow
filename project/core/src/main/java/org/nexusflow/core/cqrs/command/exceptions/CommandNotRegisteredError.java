package org.nexusflow.core.cqrs.command.exceptions;

import org.nexusflow.core.cqrs.command.Command;
import org.nexusflow.core.ddd.exceptions.DomainError;

import java.io.Serial;

public final class CommandNotRegisteredError extends DomainError {
    @Serial
    private static final long serialVersionUID = 1L;

    public CommandNotRegisteredError(Command<?> command) {
        super("command_not_registered", String.format("The command <%s> hasn't a command handler associated", command.toString()));
    }
}

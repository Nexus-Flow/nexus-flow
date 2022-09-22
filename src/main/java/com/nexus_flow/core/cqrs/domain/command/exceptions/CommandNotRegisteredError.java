package com.nexus_flow.core.cqrs.domain.command.exceptions;

import com.nexus_flow.core.cqrs.domain.command.Command;

public final class CommandNotRegisteredError extends Exception {
    public CommandNotRegisteredError(Class<? extends Command> command) {
        super(String.format("The command <%s> hasn't a command handler associated", command.toString()));
    }
}

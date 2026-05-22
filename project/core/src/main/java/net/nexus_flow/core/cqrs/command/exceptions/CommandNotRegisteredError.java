package net.nexus_flow.core.cqrs.command.exceptions;

import java.io.Serial;
import java.util.Objects;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.ddd.exceptions.DomainError;

/** Signals that a command was dispatched without a matching registered handler. */
public final class CommandNotRegisteredError extends DomainError {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the error for the unhandled command.
     *
     * @param command command that could not be routed
     */
    public CommandNotRegisteredError(Command<?> command) {
        super(
              "command_not_registered",
              String.format(
                            "The command <%s> does not have an associated command handler",
                            Objects.requireNonNull(command, "command")));
    }
}

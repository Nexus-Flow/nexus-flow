package net.nexus_flow.core.cqrs.command.exceptions;

import java.io.Serial;
import java.util.Objects;
import net.nexus_flow.core.cqrs.command.Command;

/**
 * Signals that a command was dispatched without a matching registered handler.
 *
 * <p>This is a framework configuration error (the application wired up a dispatch without the
 * corresponding handler), not a modelled domain outcome — it never represents a business-rule
 * violation. Stack-traceless because the diagnostic value is in the command type name carried by
 * the message; the wrapper's trace would only point to the bus boundary.
 */
public final class CommandNotRegisteredError extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the error for the unhandled command.
     *
     * @param command command that could not be routed; must not be {@code null}
     */
    public CommandNotRegisteredError(Command<?> command) {
        super(
              "The command <" + Objects.requireNonNull(command, "command")
                      + "> does not have an associated command handler",
              null,
              /* enableSuppression= */ true,
              /* writableStackTrace= */ false);
    }
}

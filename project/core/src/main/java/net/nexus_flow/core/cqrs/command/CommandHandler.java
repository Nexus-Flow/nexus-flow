package net.nexus_flow.core.cqrs.command;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles command processing within a CQRS context.
 *
 * @param <T> the type of command to be handled.
 */
sealed interface CommandHandler<T extends Record, R, H extends CommandHandler<T, R, H>> permits NoReturnCommandHandler, ReturnCommandHandler {

//    static <T extends Record, R> CommandHandlerBuilder.ResponseHandleFunctionStep<T, R> builder() {
//        return CommandHandlerBuilder.builder();
//    }
//
//    static <T extends Record> CommandHandlerBuilder.HandleFunctionStep<T> builderNoReturn() {
//        return CommandHandlerBuilder.builderNoReturn();
//    }

    /**
     * @return the priority of the command handler.
     */
    default int getPriority() {
        return 0;
    }

    /**
     * @return the acknowledgment modes supported by this handler.
     */
    default Set<AcknowledgeMode> getAckModes() {
        return Set.of(AcknowledgeMode.AUTO);
    }

    default InitializationType getInitializationType() {
        return InitializationType.LAZY;
    }

    /**
     * @return the concurrency level for handling commands.
     */
    default int getConcurrencyLevel() {
        return 0;
    }

    /**
     * @return the error handler for this command handler.
     */
    default CommandErrorHandler getErrorHandler() {
        return e -> {
            // Log the error at least, so it's not swallowed silently
            Logger logger = Logger.getLogger(this.getClass().getName());
            logger.log(Level.SEVERE, "Unhandled error: ", e);
        };
    }

    /**
     * Determines if this handler is a saga.
     *
     * @return true if it is part of a saga.
     */
    default boolean isSagaEnabled() {
        return false;
    }


    /**
     * Determines if this handler can trigger other commands.
     *
     * @return true if other commands can be triggered.
     */
    default boolean canTriggerOtherCommands() {
        return true;
    }

    /**
     * Determines if this handler can be invoked from other commands.
     *
     * @return true if this handler can be invoked from other commands.
     */
    default boolean canBeInvokedFromOtherCommands() {
        return true;
    }

    default CommandSettings getCommandSettings() {
        return new CommandSettings();
    }

    /**
     * Handles compensation logic in case of a failure.
     *
     * @param command the command that requires compensation.
     */
    default void handleCompensation(T command) {
        // Implement compensation logic for rollbacks if necessary.
    }

    // Método para obtener la marca de tiempo de creación del manejador
    default long getCreationTimestamp() {
        return System.currentTimeMillis();
    }

}

package net.nexus_flow.core.cqrs.command;

/**
 * Sealed root of the return command handler hierarchy.
 *
 * <p>{@link #getInternal()} provides a type-safe deconstruction point for the runtime, replacing
 * {@code instanceof Abstract*} ladders.
 */
public sealed interface ReturnCommandHandler<T extends Record, R>
        extends CommandHandler<T, R, ReturnCommandHandler<T, R>>
        permits AbstractReturnCommandHandler, ReturnCommandHandlerInternal {

    /** Returns the canonical {@link ReturnCommandHandlerInternal} representation of this handler. */
    ReturnCommandHandlerInternal<T, R> getInternal();
}

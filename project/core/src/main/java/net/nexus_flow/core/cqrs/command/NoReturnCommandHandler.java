package net.nexus_flow.core.cqrs.command;

/**
 * Sealed root of the no-return command handler hierarchy.
 *
 * <p>The {@link #getInternal()} method gives the runtime a type-safe way to obtain the executable
 * form of any handler without resorting to {@code instanceof Abstract*} ladders. Each permitted
 * subtype is responsible for returning its own {@link NoReturnCommandHandlerInternal}
 * representation.
 */
public sealed interface NoReturnCommandHandler<T extends Record>
        extends CommandHandler<T, Void, NoReturnCommandHandler<T>>
        permits AbstractNoReturnCommandHandler, NoReturnCommandHandlerInternal {

    /**
     * Returns the canonical {@link NoReturnCommandHandlerInternal} representation of this handler.
     * The runtime calls this method to obtain the executable form, replacing the previous {@code
     * instanceof}-ladder discriminator.
     */
    NoReturnCommandHandlerInternal<T> getInternal();
}

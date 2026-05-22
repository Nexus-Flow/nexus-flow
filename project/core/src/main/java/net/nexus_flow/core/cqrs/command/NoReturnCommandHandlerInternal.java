package net.nexus_flow.core.cqrs.command;

/**
 * Executable no-return command handler contract used by the runtime.
 *
 * <p>Adapters may implement this interface directly when they can already supply the executable
 * {@link Runnable} form of a handler invocation.
 *
 * @param <T> command payload type
 */
@FunctionalInterface
public non-sealed interface NoReturnCommandHandlerInternal<T extends Record>
        extends NoReturnCommandHandler<T> {

    /**
     * Builds the executable work item for the supplied command body.
     *
     * @param command command payload to handle
     * @return runnable that performs the handler invocation
     */
    Runnable handle(T command);

    /** Internal handlers are already in their executable form. */
    @Override
    default NoReturnCommandHandlerInternal<T> getInternal() {
        return this;
    }
}

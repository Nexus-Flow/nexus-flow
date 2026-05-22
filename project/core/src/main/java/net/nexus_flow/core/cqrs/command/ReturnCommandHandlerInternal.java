package net.nexus_flow.core.cqrs.command;

import java.util.concurrent.Callable;

/**
 * Executable request/response command handler contract used by the runtime.
 *
 * <p>Adapters may implement this interface directly when they can already expose the executable
 * {@link Callable} for a command invocation.
 *
 * @param <T> command payload type
 * @param <R> response type
 */
@FunctionalInterface
public non-sealed interface ReturnCommandHandlerInternal<T extends Record, R>
        extends ReturnCommandHandler<T, R> {

    /**
     * Builds the executable work item for the supplied command body.
     *
     * @param command command payload to handle
     * @return callable that performs the handler invocation and returns the response
     */
    Callable<R> handleAndReturn(T command);

    /** Internal handlers are already in their executable form. */
    @Override
    default ReturnCommandHandlerInternal<T, R> getInternal() {
        return this;
    }
}

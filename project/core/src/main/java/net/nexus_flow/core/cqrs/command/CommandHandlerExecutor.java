package net.nexus_flow.core.cqrs.command;

import java.util.List;

/**
 * Internal executor contract behind every registered command handler. Owns the worker pool, the
 * priority queue, the back-pressure gate and the cooperative-cancellation loop for one command type
 * within one {@link net.nexus_flow.core.runtime.FlowRuntime}.
 *
 * <p>Implemented by {@link DefaultCommandHandlerExecutor}; the interface exists so {@link
 * CommandConsumerRegistry} and {@link DefaultCommandBus} can hold the executor through a single
 * shape regardless of whether the underlying handler returns a value.
 */
interface CommandHandlerExecutor<T extends Record, R, H extends CommandHandler<T, R, H>> {

    /**
     * Enqueue a single fire-and-forget dispatch. Returns once the command has been admitted to the
     * queue (or rejected by the back-pressure gate); does not wait for the handler body.
     */
    void execute(Command<T> command);

    /**
     * Enqueue a batch of fire-and-forget dispatches in priority order. Each command is admitted (or
     * rejected) independently by the back-pressure gate.
     */
    void execute(List<Command<T>> command);

    /**
     * Enqueue a single command and block the caller until the handler body completes, returning the
     * handler's value.
     *
     * @return the handler return value
     */
    R submitAndReturn(Command<T> command);

    /**
     * Enqueue a batch of commands and block until every handler body has completed, returning the
     * results in the same order as the input list.
     */
    List<R> submitAndReturn(List<Command<T>> command);

    /**
     * Re-tune the worker pool to {@code newConcurrencyLevel} permits without dropping any in-flight
     * dispatch. May grow or shrink the pool atomically.
     */
    void adjustConcurrency(int newConcurrencyLevel);

    /**
     * Stop accepting new dispatches, drain in-flight work according to the runtime's shutdown policy,
     * and release all resources held by the executor. Idempotent.
     */
    void close();
}

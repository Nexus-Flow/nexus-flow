package net.nexus_flow.core.cqrs.command;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.runtime.*;
import net.nexus_flow.core.runtime.result.DispatchResult;

/**
 * Per-runtime command bus.
 *
 * <p>Instances are owned by {@link net.nexus_flow.core.runtime.FlowRuntime}; obtain one via {@code
 * runtime.commands()}. There is no process-wide accessor: see {@link PerRuntime} for the
 * architectural invariant pinned by {@code NoStaticGetInstanceTest}.
 */
@PerRuntime
public sealed interface CommandBus permits DefaultCommandBus {

    /**
     * build a fresh, runtime-scoped {@link CommandBus}. Internal API: called from {@code
     * DefaultFlowRuntime}. The resulting bus owns a private {@code CommandConsumerRegistry} and is
     * released on {@link #closeAll()}.
     */
    static CommandBus newInstance(EventBus eventBus, ExecutorService executor, FlowRuntime runtime) {
        return new DefaultCommandBus(
                new DefaultCommandConsumerRegistry(eventBus, executor, runtime), eventBus, runtime);
    }

    /**
     * Registers a fire-and-forget command handler.
     *
     * @param <T>     the command payload type (must be a {@link Record})
     * @param handler the handler to register
     * @throws IllegalArgumentException if a handler for this command type is already registered
     */
    <T extends Record> void register(NoReturnCommandHandler<T> handler);

    /**
     * Registers a request/response command handler.
     *
     * @param <T>     the command payload type (must be a {@link Record})
     * @param <R>     the response type
     * @param handler the handler to register
     * @throws IllegalArgumentException if a handler for this command type is already registered
     */
    <T extends Record, R> void register(ReturnCommandHandler<T, R> handler);

    /**
     * Unregisters a fire-and-forget command handler.
     *
     * @param <T>     the command payload type (must be a {@link Record})
     * @param handler the handler to unregister
     */
    <T extends Record> void unregister(NoReturnCommandHandler<T> handler);

    /**
     * Unregisters a request/response command handler.
     *
     * @param <T>     the command payload type (must be a {@link Record})
     * @param <R>     the response type
     * @param handler the handler to unregister
     */
    <T extends Record, R> void unregister(ReturnCommandHandler<T, R> handler);

    /**
     * Dispatches a fire-and-forget command asynchronously.
     *
     * <p>The handler is invoked with default {@link ExecutionContext} and {@link ErrorPolicy}. Any
     * domain events published during handling are fanned out to registered listeners.
     *
     * @param <T>     the command payload type (must be a {@link Record})
     * @param command the command to dispatch
     * @throws net.nexus_flow.core.cqrs.command.exceptions.CommandNotRegisteredError if no handler is
     *                                                                               registered for this command type
     */
    <T extends Record> void dispatch(Command<T> command);

    /**
     * Dispatches a fire-and-forget command with an explicit {@link ExecutionContext}.
     *
     * <p>Binds {@code ctx} as the current {@link FlowScope#CURRENT_CONTEXT} for the duration of the
     * dispatch so that the underlying handler executor and any cross-cutting infrastructure (logging,
     * tracing, cancellation) observe the caller's context without requiring an explicit parameter on
     * the fire-and-forget path.
     *
     * <p>This is the preferred overload for framework workers (outbox, scheduler, saga runner) that
     * own a worker-lifetime {@link ExecutionContext} and dispatch commands without awaiting a result.
     * The {@link #dispatch(Command)} overload uses whatever context {@link FlowScope#current()}
     * currently exposes, or a root context if none is bound.
     *
     * @param <T>     the command payload type (must be a {@link Record})
     * @param command the command to dispatch; must not be {@code null}
     * @param ctx     the execution context to bind during dispatch; must not be {@code null}
     * @throws net.nexus_flow.core.cqrs.command.exceptions.CommandNotRegisteredError if no handler is
     *                                                                               registered for this command type
     */
    default <T extends Record> void dispatch(Command<T> command, ExecutionContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        FlowScope.runWithContext(ctx, () -> dispatch(command));
    }

    /**
     * Dispatches a request/response command synchronously.
     *
     * <p>Blocks until the handler completes, returning the response. Any domain events published
     * during handling are fanned out to registered listeners. Command-level backpressure and
     * saturation policies apply.
     *
     * @param <T>     the command payload type (must be a {@link Record})
     * @param <R>     the response type
     * @param command the command to dispatch
     * @return the handler's response
     * @throws net.nexus_flow.core.cqrs.command.exceptions.CommandNotRegisteredError    if no handler is
     *                                                                                  registered for this command type
     * @throws net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError if handler
     *                                                                                  execution fails
     */
    <T extends Record, R> R dispatchAndReturn(Command<T> command);

    /**
     * Structured result-based dispatch. Wraps the handler in {@link
     * net.nexus_flow.core.runtime.FlowScope}, classifies any {@link Throwable} into the {@link
     * net.nexus_flow.core.runtime.result.FlowError} taxonomy, and publishes the events drained from
     * the handler under the supplied {@link ErrorPolicy}.
     *
     * <p>The legacy {@link #dispatchAndReturn(Command)} keeps its current behavior and contract; this
     * method is additive.
     *
     * @param command the command to dispatch
     * @param ctx     active execution context (use {@link ExecutionContext#root()} for top-level callers)
     * @param policy  error policy for command-driven event fan-out
     */
    <T extends Record, R> DispatchResult<R> dispatchAndReturnResult(
            Command<T> command, ExecutionContext ctx, ErrorPolicy policy);

    /**
     * Convenience overload — uses {@link ExecutionContext#root()} and {@link ErrorPolicy#failFast()}.
     *
     * @param command the command to dispatch
     * @param <T>     command payload type
     * @param <R>     handler response type
     * @return structured dispatch result
     */
    default <T extends Record, R> DispatchResult<R> dispatchAndReturnResult(Command<T> command) {
        return dispatchAndReturnResult(command, ExecutionContext.root(), ErrorPolicy.failFast());
    }

    /**
     * release every registered handler executor. Called from {@link FlowRuntime#close()}. Not
     * intended for direct use by application code; the runtime owns the bus lifecycle.
     */
    void closeAll();

    /**
     * diagnostic snapshot of registered command handlers. Mirrors the shape of {@link
     * net.nexus_flow.core.runtime.registry.DispatchPlan DispatchPlan} on the event side so
     * observability tooling sees a unified read-only view of every dispatch target on the runtime.
     *
     * <p>The returned record is a value object: mutating the bus after the snapshot is captured does
     * not change the snapshot.
     */
    CommandRegistrationSnapshot registrationSnapshot();
}

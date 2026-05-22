package net.nexus_flow.core.cqrs.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError;
import net.nexus_flow.core.cqrs.event.DomainEventContext;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.observability.jfr.CommandDispatchEvent;
import net.nexus_flow.core.runtime.*;
import net.nexus_flow.core.runtime.dispatch.InvocationContext;
import net.nexus_flow.core.runtime.dispatch.InvocationKind;
import net.nexus_flow.core.runtime.dispatch.SyncDispatcher;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.types.TypeReference;
import org.jspecify.annotations.Nullable;

/**
 * Per-runtime default implementation of {@link CommandBus}.
 *
 * <p>Constructed by {@link FlowRuntime} with explicit dependencies — no static {@code
 * getInstance()} chains. The {@code consumerRegistry}, {@code eventBus} and {@code runtime}
 * references all belong to the same {@link FlowRuntime} so a dispatch never crosses runtime
 * boundaries.
 */
@PerRuntime
non-sealed class DefaultCommandBus implements CommandBus {

    private final CommandConsumerRegistry consumerRegistry;
    private final EventBus                eventBus;
    private final FlowRuntime             runtime;

    DefaultCommandBus(
            CommandConsumerRegistry consumerRegistry, EventBus eventBus, FlowRuntime runtime) {
        this.consumerRegistry = Objects.requireNonNull(consumerRegistry, "consumerRegistry");
        this.eventBus         = Objects.requireNonNull(eventBus, "eventBus");
        this.runtime          = Objects.requireNonNull(runtime, "runtime");
    }

    /** {@inheritDoc} */
    @Override
    public void closeAll() {
        consumerRegistry.closeAll();
    }

    /** {@inheritDoc} */
    @Override
    public CommandRegistrationSnapshot registrationSnapshot() {
        return consumerRegistry.snapshot();
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record> void register(NoReturnCommandHandler<T> handler) {
        NoReturnCommandHandler<T> typedHandler = Objects.requireNonNull(handler, "handler");
        TypeReference<T>          type         = noReturnCommandTypeOf(typedHandler);
        if (type == null) {
            // Legacy Internal-only handler without CommandTypeAware: silently
            // ignore to preserve backward compatibility for SPI adapters.
            return;
        }
        consumerRegistry.createPublisher(type, typedHandler);
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record, R> void register(ReturnCommandHandler<T, R> handler) {
        ReturnCommandHandler<T, R> typedHandler = Objects.requireNonNull(handler, "handler");
        TypeReference<T>           type         = returnCommandTypeOf(typedHandler);
        if (type == null) {
            return; // see no-return register() for rationale
        }
        consumerRegistry.createPublisher(type, typedHandler);
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record> void unregister(NoReturnCommandHandler<T> handler) {
        TypeReference<T> type = noReturnCommandTypeOf(Objects.requireNonNull(handler, "handler"));
        if (type == null) {
            return;
        }
        consumerRegistry.clearNoReturnPublisher(type);
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record, R> void unregister(ReturnCommandHandler<T, R> handler) {
        TypeReference<T> type = returnCommandTypeOf(Objects.requireNonNull(handler, "handler"));
        if (type == null) {
            return;
        }
        consumerRegistry.clearReturnPublisher(type);
    }

    private <T extends Record> @Nullable TypeReference<T> noReturnCommandTypeOf(
            NoReturnCommandHandler<T> handler) {
        return switch (handler) {
            case AbstractNoReturnCommandHandler<T> abs                                                         -> abs.getCommandType();
            case NoReturnCommandHandlerInternal<T> internal when internal instanceof CommandTypeAware<?> typed ->
                 this.<T>typedRef(typed);
            // Legacy Internal-only handlers carry no TypeReference; return null
            // here so the caller silently ignores them, preserving backward
            // compatibility with future Phase-6 SPI adapters (Spring, Quarkus,
            // build-time generated handlers, ...) that wire their own routing.
            case NoReturnCommandHandlerInternal<T> _ -> null;
        };
    }

    private <T extends Record, R> @Nullable TypeReference<T> returnCommandTypeOf(
            ReturnCommandHandler<T, R> handler) {
        return switch (handler) {
            case AbstractReturnCommandHandler<T, R> abs                                                         -> abs.getCommandType();
            case ReturnCommandHandlerInternal<T, R> internal when internal instanceof CommandTypeAware<?> typed ->
                 this.<T>typedRef(typed);
            // see noReturnCommandTypeOf for the silent-ignore rationale.
            case ReturnCommandHandlerInternal<T, R> _ -> null;
        };
    }

    /**
     * Narrow the wildcard captured by pattern matching on {@link CommandTypeAware} back to the
     * call-site's {@code T}. Safe because every {@code CommandTypeAware<?>} that the bus accepts is
     * also typed compatibly with the surrounding handler's {@code T}.
     */
    @SuppressWarnings("unchecked")
    private <T extends Record> TypeReference<T> typedRef(CommandTypeAware<?> typed) {
        return (TypeReference<T>) typed.getCommandType();
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record> void dispatch(Command<T> command) {
        Command<T>                                                        typedCommand = Objects.requireNonNull(command, "command");
        DefaultCommandHandlerExecutor<T, Void, NoReturnCommandHandler<T>> executor     =
                consumerRegistry.getNoReturnPublisher(typedCommand.getType());
        if (executor == null) {
            // Wrap so callers that catch CommandHandlerExecutionError to handle
            // "no handler" symmetrically with handler execution failures keep
            // working. CommandNotRegisteredError is recovered via getCause().
            throw new CommandHandlerExecutionError(
                    new net.nexus_flow.core.cqrs.command.exceptions.CommandNotRegisteredError(typedCommand));
        }
        try {
            executor.execute(typedCommand);
        } catch (CommandHandlerExecutionError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CommandHandlerExecutionError(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record, R> R dispatchAndReturn(Command<T> command) {
        Command<T>                                                      typedCommand = Objects.requireNonNull(command, "command");
        DefaultCommandHandlerExecutor<T, R, ReturnCommandHandler<T, R>> executor     =
                consumerRegistry.getReturnPublisher(typedCommand.getType());
        if (executor == null) {
            throw new CommandHandlerExecutionError(
                    new net.nexus_flow.core.cqrs.command.exceptions.CommandNotRegisteredError(typedCommand));
        }
        try {
            return executor.submitAndReturn(typedCommand);
        } catch (CommandHandlerExecutionError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CommandHandlerExecutionError(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record, R> DispatchResult<R> dispatchAndReturnResult(
            Command<T> command, ExecutionContext ctx, ErrorPolicy policy) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(policy, "policy");

        // route through the owning runtime's interceptor
        // onion (no more FlowRuntimeContext.current()). Zero
        // interceptors == byte-identical fast path
        // (see SyncDispatcher.dispatchThrough).
        List<net.nexus_flow.core.runtime.dispatch.DispatchInterceptor> interceptors =
                runtime.interceptors();
        InvocationContext                                              invCtx       =
                InvocationContext.of(InvocationKind.COMMAND, command.getBody(), ctx, policy);

        // JFR custom event around the whole intercepted dispatch.
        // begin()/end() must always be paired; end() and the shouldCommit() gate
        // live in the finally block so a rare Error propagation (OOM, etc.) still
        // closes the recording slot correctly instead of leaking an open event.
        CommandDispatchEvent jfr = new CommandDispatchEvent();
        jfr.begin();
        DispatchResult<R> result    = null;
        Throwable         jfrThrown = null;
        try {
            result =
                    SyncDispatcher.dispatchThrough(
                                                   invCtx, interceptors, () -> dispatchAndReturnResultBody(command, ctx, policy));
            return result;
        } catch (Throwable t) {
            jfrThrown = t;
            throw t;
        } finally {
            jfr.end();
            if (jfr.shouldCommit()) {
                jfr.commandType = command.getType().getType().getTypeName();
                if (jfrThrown != null) {
                    jfr.outcome      = "Failure";
                    jfr.failureClass = jfrThrown.getClass().getName();
                } else if (result != null) {
                    jfr.outcome      = outcomeOf(result);
                    jfr.failureClass = failureClassOf(result);
                }
                jfr.commit();
            }
        }
    }

    private static String outcomeOf(DispatchResult<?> r) {
        return switch (r) {
            case DispatchResult.Success<?> _        -> "Success";
            case DispatchResult.Failure<?> f        ->
                 f.cause() instanceof net.nexus_flow.core.cqrs.command.exceptions.CommandNotRegisteredError ? "NotRegistered" : "Failure";
            case DispatchResult.PartialFailure<?> _ -> "PartialFailure";
            case DispatchResult.Accepted<?> _       -> "Accepted";
        };
    }

    private static @Nullable String failureClassOf(DispatchResult<?> r) {
        return switch (r) {
            case DispatchResult.Success<?> _,DispatchResult.Accepted<?> _ -> null;
            case DispatchResult.Failure<?> f                              -> f.cause() == null ? null : f.cause().getClass().getName();
            case DispatchResult.PartialFailure<?> p                       ->
                 p.failures().isEmpty() ? null : p.failures().getFirst().getClass().getName();
        };
    }

    private <T extends Record, R> DispatchResult<R> dispatchAndReturnResultBody(
            Command<T> command, ExecutionContext ctx, ErrorPolicy policy) {
        DefaultCommandHandlerExecutor<T, R, ReturnCommandHandler<T, R>> executor =
                consumerRegistry.getReturnPublisher(command.getType());
        if (executor == null) {
            return DispatchResult.failure(
                                          new net.nexus_flow.core.cqrs.command.exceptions.CommandNotRegisteredError(command));
        }

        // Step 1: invoke handler under FlowScope, classifying failures.
        DispatchResult<R> handlerResult =
                SyncDispatcher.invoke(
                                      () -> {
                                          try {
                                              return executor.submitAndReturn(command);
                                          } catch (CommandHandlerExecutionError che) {
                                              // The executor wraps every handler exception in
                                              // CommandHandlerExecutionError; unwrap so the
                                              // "no wrapping for FlowError.Domain" contract holds.
                                              Throwable cause = che.getCause();
                                              if (cause instanceof RuntimeException re) {
                                                  throw ThrowableUtils.withSuppressed(re, che);
                                              }
                                              if (cause != null) {
                                                  throw ThrowableUtils.withSuppressed(new RuntimeException(cause), che);
                                              }
                                              throw che;
                                          }
                                      },
                                      ctx,
                                      policy);

        if (!(handlerResult instanceof DispatchResult.Success<R> ok)) {
            return handlerResult;
        }

        // Step 2: drain the events recorded during the handler run and
        // publish them through the structured fan-out. Prefer
        // the aggregate-local list (CommandResult-carried events); fall
        // back to the JVM-wide {@link DomainEventContext} sink for handlers
        // that have not migrated yet.
        //
        // when the runtime carries an OutboxConfig, the
        // executor has already appended the drained events to the
        // outbox (see HandlerEventDrain#drain). If the
        // kill-switch is ON the executor also cleared the sink, so
        // {@code sink.hasEventsRecorded()} returns false here and
        // the inline fan-out is naturally skipped. If the kill-switch
        // is OFF the executor used the legacy dispatch on the sink,
        // and we still see the events for backwards compatibility.
        DomainEventContext sink   = DomainEventContext.current();
        List<DomainEvent>  events = List.of();
        if (sink.hasEventsRecorded()) {
            events = new ArrayList<>(sink.getEvents());
            sink.clearEvents();
        }
        if (events.isEmpty()) {
            return ok;
        }

        DispatchResult<Void> fanOut = SyncDispatcher.publishEvents(events, ctx, policy, eventBus);

        return switch (fanOut) {
            case DispatchResult.Success<Void> _        -> ok;
            case DispatchResult.Failure<Void> f        -> DispatchResult.failure(f.cause());
            case DispatchResult.PartialFailure<Void> p ->
                 DispatchResult.partial(ok.value(), p.failures());
            // Accepted at the fan-out boundary: the
            // command's events were handed off to the outbox. The
            // command's own value is unchanged; the durable receipt
            // does not propagate up (callers observe it through the
            // outbox row / message id).
            case DispatchResult.Accepted<Void> _ -> ok;
        };
    }
}

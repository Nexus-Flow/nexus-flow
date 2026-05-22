package net.nexus_flow.core.cqrs.command;

import java.lang.reflect.Method;
import java.util.Objects;
import net.nexus_flow.core.cqrs.introspection.CommandHandlerRegistration;
import org.jspecify.annotations.Nullable;

/**
 * Sealed root of the command-handler hierarchy.
 *
 * <p>Three layered entry points exist for building handlers — pick the one that matches your need:
 *
 * <ol>
 * <li><strong>Ergonomic DSL</strong> — {@link #forCommand(Class)} (recommended for inline
 * handlers, 95% of cases).
 * <li><strong>Lower-level builder</strong> — {@link #builder(CommandTypeSignature)} or {@link
 * #builderNoReturn(CommandTypeSignature)} (when you need {@code withPriority(...)} / {@code
 *       withConcurrencyLevel(...)}).
 * <li><strong>Abstract subclass</strong> — extend {@link AbstractReturnCommandHandler} or {@link
 * AbstractNoReturnCommandHandler} (when the handler owns internal state, or you prefer named
 * classes).
 * </ol>
 *
 * @param <T> the command payload (must be a {@link Record})
 * @param <R> the response type ({@link Void} for fire-and-forget)
 * @param <H> recursive self-type for the sealed hierarchy
 */
public sealed interface CommandHandler<T extends Record, R, H extends CommandHandler<T, R, H>>
        permits NoReturnCommandHandler, ReturnCommandHandler {

    /**
     * <strong>Preferred</strong> ergonomic entry point for assembling an inline command handler.
     *
     * <p>Two terminal shapes are available:
     *
     * <p>
     *
     * {@snippet :
     * // Fire-and-forget
     * var ship = CommandHandler.forCommand(ShipOrder.class)
     *         .handle(cmd -> shippingService.ship(cmd.orderId()));
     *
     * // Request/response
     * var create = CommandHandler.forCommand(CreateOrder.class)
     *         .returns(OrderId.class)
     *         .handle(cmd -> new OrderId(cmd.id()));
     *
     * // Parameterised response (super-type token only when needed)
     * var list = CommandHandler.forCommand(GetOrders.class)
     *         .returns(new net.nexus_flow.core.types.TypeReference<java.util.List<OrderId>>() {
     *         })
     *         .handle(cmd -> repo.findAll(cmd));
     * }
     *
     * <p>For per-handler tuning ({@code withPriority(...)}, {@code withConcurrencyLevel(...)}) use
     * the lower-level {@link #builder(CommandTypeSignature)} entry point.
     */
    static <T extends Record> CommandHandlerDsl.CommandStep<T> forCommand(Class<T> commandType) {
        return CommandHandlerDsl.forCommand(commandType);
    }

    /**
     * Build a command handler from an already-discovered Java method.
     *
     * <p>This is the public SPI bridge for IoC integrations: a Spring {@code BeanPostProcessor}, for
     * example, can discover a method annotated with its own {@code @CommandHandler}, call this
     * factory, inspect the returned registration token, and register it through {@link
     * CommandHandlerRegistration#registerOn(CommandBus)}. Void methods produce {@link
     * NoReturnCommandHandler}s; value-returning methods produce {@link ReturnCommandHandler}s.
     *
     * @param target bean / object that owns {@code method}
     * @param method method with exactly one {@link Record} parameter
     */
    static CommandHandlerRegistration fromMethod(Object target, Method method) {
        return fromMethod(target, method, CommandHandlerOptions.DEFAULTS);
    }

    /** Build a method-backed command handler with explicit execution options. */
    static CommandHandlerRegistration fromMethod(
            Object target, Method method, CommandHandlerOptions options) {
        Objects.requireNonNull(options, "options");
        return CommandHandlerMethodAdapter.fromMethod(target, method, options);
    }

    /**
     * Lower-level fluent entry point for building an inline <em>return</em> command handler — use
     * this when you need {@code withPriority(...)} or {@code withConcurrencyLevel(...)}, or when the
     * response type is parameterised and you prefer a super-type token over {@link
     * #forCommand(Class)}'s overload.
     *
     * <p>
     *
     * {@snippet :
     * var handler = CommandHandler.builder(new CommandTypeSignature<CreateOrder, OrderId>() {
     * })
     *         .withHandleFunctionResponse(cmd -> new OrderId(cmd.id()))
     *         .withPriority(10)
     *         .build();
     * commandBus.register(handler);
     * }
     */
    static <T extends Record, R> CommandHandlerBuilder.ResponseHandleFunctionStep<T, R> builder(
            CommandTypeSignature<T, R> typeSignature) {
        return CommandHandlerBuilder.builder(typeSignature);
    }

    /**
     * Lower-level fluent entry point for building an inline <em>no-return</em> command handler. See
     * {@link #builder(CommandTypeSignature)} for the rationale.
     *
     * <p>
     *
     * {@snippet :
     * var handler = CommandHandler.builderNoReturn(new CommandTypeSignature<ShipOrder, Void>() {
     * })
     *         .withHandleFunction(cmd -> shippingService.ship(cmd.orderId()))
     *         .build();
     * }
     */
    static <T extends Record> CommandHandlerBuilder.HandleFunctionStep<T> builderNoReturn(
            CommandTypeSignature<T, Void> typeSignature) {
        return CommandHandlerBuilder.builderNoReturn(typeSignature);
    }

    /** Scheduling priority within the per-handler queue (higher = sooner). */
    default int getPriority() {
        return 0;
    }

    /** Whether the runtime should pre-start drainer carriers for this handler. */
    default InitializationType getInitializationType() {
        return InitializationType.LAZY;
    }

    /**
     * Number of carrier threads to run in parallel for this handler. Zero means "no per-handler
     * queue" (synchronous inline execution).
     */
    default int getConcurrencyLevel() {
        return 0;
    }

    /** Whether this handler participates in a saga. */
    default boolean isSagaEnabled() {
        return false;
    }

    /** Per-handler execution settings (backpressure, mode, etc.). */
    default CommandSettings getCommandSettings() {
        return new CommandSettings();
    }

    /**
     * Optional per-handler default {@link net.nexus_flow.core.runtime.ErrorPolicy} used when the
     * dispatch caller does not provide one explicitly. The {@link DefaultCommandHandlerExecutor}
     * checks this before falling back to the bus-level default.
     *
     * <p>Return {@code null} to indicate "use the caller's policy" (default).
     */
    // Returns null intentionally as the default; handlers override to provide a custom policy.
    //noinspection SameReturnValue
    default net.nexus_flow.core.runtime.@Nullable ErrorPolicy defaultErrorPolicy() {
        return null;
    }
}

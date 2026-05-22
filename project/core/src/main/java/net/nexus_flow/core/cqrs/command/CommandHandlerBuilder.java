package net.nexus_flow.core.cqrs.command;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import net.nexus_flow.core.types.TypeReference;
import org.jspecify.annotations.Nullable;

/**
 * Fluent builder for inline {@link CommandHandler} instances.
 *
 * <p>Entry points live on {@link CommandHandler#builder(CommandTypeSignature)} and {@link
 * CommandHandler#builderNoReturn(CommandTypeSignature)}, which delegate here. The {@link
 * CommandTypeSignature} parameter is the super-type token that captures {@code <T, R>} at compile
 * time so the resulting handler can be registered with {@code CommandBus.register(...)} without
 * losing its routing key.
 *
 * <p><strong>Why a super-type token?</strong> Java erases generics at runtime. A {@code static
 * <T,R> ... builder()} would receive fresh unbound type variables that the compiler ultimately
 * resolves to the upper bound ({@code Record} for {@code T}, {@code Object} for {@code R}), and the
 * framework would have no {@link Class} to route by. Capturing the types via an anonymous subclass
 * of {@code CommandTypeSignature} preserves them in the {@code Signature} bytecode attribute, which
 * the JDK exposes via {@link Class#getGenericSuperclass()}.
 */
public class CommandHandlerBuilder<T extends Record, R> extends CommandTypeSignature<T, R> {

    private @Nullable Consumer<T>    handleFunction;
    private @Nullable Function<T, R> handleFunctionWithResponse;
    private int                      priority;
    private int                      concurrencyLevel;

    /**
     * Internal constructor used by the static factories to inherit the type tokens from a
     * caller-provided {@link CommandTypeSignature} without requiring the builder to be anonymously
     * subclassed itself.
     */
    CommandHandlerBuilder(CommandTypeSignature<T, R> typeSignature) {
        super(typeSignature);
    }

    /**
     * Entry point for the return-value builder. Call from {@link
     * CommandHandler#builder(CommandTypeSignature)}.
     *
     * @param typeSignature anonymous-subclass token capturing {@code <T, R>}
     * @return fluent builder positioned at the {@link ResponseHandleFunctionStep}
     */
    public static <T extends Record, R> ResponseHandleFunctionStep<T, R> builder(
            CommandTypeSignature<T, R> typeSignature) {
        Objects.requireNonNull(typeSignature, "typeSignature");
        var b = new CommandHandlerBuilder<>(typeSignature);
        return b.new ReturnBuilderSteps();
    }

    /**
     * Entry point for the no-return builder. Call from {@link
     * CommandHandler#builderNoReturn(CommandTypeSignature)}.
     *
     * @param typeSignature anonymous-subclass token capturing {@code <T, Void>}
     * @return fluent builder positioned at the {@link HandleFunctionStep}
     */
    public static <T extends Record> HandleFunctionStep<T> builderNoReturn(
            CommandTypeSignature<T, Void> typeSignature) {
        Objects.requireNonNull(typeSignature, "typeSignature");
        var b = new CommandHandlerBuilder<>(typeSignature);
        return b.new NoReturnBuilderSteps();
    }

    /** Step that captures the no-return handler function. */
    @FunctionalInterface
    public interface HandleFunctionStep<T extends Record> {

        /**
         * Supplies the fire-and-forget handler function.
         *
         * @param handleFunction handler logic to run for each command
         * @return next builder step
         */
        NoReturnBuildStep<T> withHandleFunction(Consumer<T> handleFunction);
    }

    /** Step that captures the return-value handler function. */
    @FunctionalInterface
    public interface ResponseHandleFunctionStep<T extends Record, R> {

        /**
         * Supplies the handler function that returns a response.
         *
         * @param handleFunctionWithResponse handler logic to run for each command
         * @return next builder step
         */
        ReturnBuildStep<T, R> withHandleFunctionResponse(Function<T, R> handleFunctionWithResponse);
    }

    /** Shared build step for optional tuning knobs and final handler creation. */
    public interface BuildStep<T extends Record, R, S extends BuildStep<T, R, S>> {

        /**
         * Sets the handler priority.
         *
         * @param priority handler priority; higher values run sooner
         * @return this builder step
         */
        S withPriority(int priority);

        /**
         * Sets the handler concurrency level.
         *
         * @param concurrencyLevel max in-flight handler executions
         * @return this builder step
         */
        S withConcurrencyLevel(int concurrencyLevel);

        /**
         * Builds the configured handler.
         *
         * @return command handler instance
         */
        CommandHandler<T, R, ?> build();
    }

    /** Build step specialization for fire-and-forget handlers. */
    public interface NoReturnBuildStep<T extends Record>
            extends BuildStep<T, Void, NoReturnBuildStep<T>> {

        /**
         * Builds the configured no-return handler.
         *
         * @return no-return command handler
         */
        @Override
        NoReturnCommandHandler<T> build();
    }

    /** Build step specialization for request/response handlers. */
    public interface ReturnBuildStep<T extends Record, R>
            extends BuildStep<T, R, ReturnBuildStep<T, R>> {

        /**
         * Builds the configured return-value handler.
         *
         * @return return-value command handler
         */
        @Override
        ReturnCommandHandler<T, R> build();
    }

    /**
     * Inline no-return handler emitted by the builder. Implements {@link CommandTypeAware} so {@link
     * DefaultCommandBus} can extract the routing key during registration even though this is an
     * {@code Internal} variant rather than an {@link AbstractNoReturnCommandHandler} subclass.
     */
    static final class NoReturnCommandHandlerImpl<T extends Record>
            implements NoReturnCommandHandlerInternal<T>, CommandTypeAware<T> {
        private final Consumer<T>           handler;
        private final TypeReference<T>      commandType;
        private final CommandHandlerOptions options;

        NoReturnCommandHandlerImpl(
                Consumer<T> handler, TypeReference<T> commandType, int priority, int concurrencyLevel) {
            this(
                 handler,
                 commandType,
                 new CommandHandlerOptions(
                         priority,
                         concurrencyLevel,
                         InitializationType.LAZY,
                         false,
                         new CommandSettings(),
                         null));
        }

        NoReturnCommandHandlerImpl(
                Consumer<T> handler, TypeReference<T> commandType, CommandHandlerOptions options) {
            this.handler     = Objects.requireNonNull(handler, "handler");
            this.commandType = Objects.requireNonNull(commandType, "commandType");
            this.options     = Objects.requireNonNull(options, "options");
        }

        /** {@inheritDoc} */
        @Override
        public Runnable handle(T command) {
            return () -> handler.accept(command);
        }

        /** {@inheritDoc} */
        @Override
        public TypeReference<T> getCommandType() {
            return commandType;
        }

        /** {@inheritDoc} */
        @Override
        public int getPriority() {
            return options.priority();
        }

        /** {@inheritDoc} */
        @Override
        public int getConcurrencyLevel() {
            return options.concurrencyLevel();
        }

        /** {@inheritDoc} */
        @Override
        public InitializationType getInitializationType() {
            return options.initializationType();
        }

        /** {@inheritDoc} */
        @Override
        public boolean isSagaEnabled() {
            return options.sagaEnabled();
        }

        /** {@inheritDoc} */
        @Override
        public CommandSettings getCommandSettings() {
            return options.commandSettings();
        }

        /** {@inheritDoc} */
        @Override
        public net.nexus_flow.core.runtime.@Nullable ErrorPolicy defaultErrorPolicy() {
            return options.defaultErrorPolicy();
        }
    }

    /**
     * Inline return handler emitted by the builder. See {@link NoReturnCommandHandlerImpl} for the
     * {@link CommandTypeAware} rationale.
     */
    static final class ReturnCommandHandlerImpl<T extends Record, R>
            implements ReturnCommandHandlerInternal<T, R>, CommandTypeAware<T> {
        private final Function<T, R>        responseHandler;
        private final TypeReference<T>      commandType;
        private final CommandHandlerOptions options;

        ReturnCommandHandlerImpl(
                Function<T, R> responseHandler,
                TypeReference<T> commandType,
                int priority,
                int concurrencyLevel) {
            this(
                 responseHandler,
                 commandType,
                 new CommandHandlerOptions(
                         priority,
                         concurrencyLevel,
                         InitializationType.LAZY,
                         false,
                         new CommandSettings(),
                         null));
        }

        ReturnCommandHandlerImpl(
                Function<T, R> responseHandler,
                TypeReference<T> commandType,
                CommandHandlerOptions options) {
            this.responseHandler = Objects.requireNonNull(responseHandler, "responseHandler");
            this.commandType     = Objects.requireNonNull(commandType, "commandType");
            this.options         = Objects.requireNonNull(options, "options");
        }

        /** {@inheritDoc} */
        @Override
        public Callable<R> handleAndReturn(T command) {
            return () -> responseHandler.apply(command);
        }

        /** {@inheritDoc} */
        @Override
        public TypeReference<T> getCommandType() {
            return commandType;
        }

        /** {@inheritDoc} */
        @Override
        public int getPriority() {
            return options.priority();
        }

        /** {@inheritDoc} */
        @Override
        public int getConcurrencyLevel() {
            return options.concurrencyLevel();
        }

        /** {@inheritDoc} */
        @Override
        public InitializationType getInitializationType() {
            return options.initializationType();
        }

        /** {@inheritDoc} */
        @Override
        public boolean isSagaEnabled() {
            return options.sagaEnabled();
        }

        /** {@inheritDoc} */
        @Override
        public CommandSettings getCommandSettings() {
            return options.commandSettings();
        }

        /** {@inheritDoc} */
        @Override
        public net.nexus_flow.core.runtime.@Nullable ErrorPolicy defaultErrorPolicy() {
            return options.defaultErrorPolicy();
        }
    }

    final class NoReturnBuilderSteps implements HandleFunctionStep<T>, NoReturnBuildStep<T> {

        /** {@inheritDoc} */
        @Override
        public NoReturnBuildStep<T> withHandleFunction(Consumer<T> handleFunction) {
            CommandHandlerBuilder.this.handleFunction = handleFunction;
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public NoReturnBuildStep<T> withPriority(int priority) {
            CommandHandlerBuilder.this.priority = priority;
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public NoReturnBuildStep<T> withConcurrencyLevel(int concurrencyLevel) {
            CommandHandlerBuilder.this.concurrencyLevel = concurrencyLevel;
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public NoReturnCommandHandler<T> build() {
            if (CommandHandlerBuilder.this.handleFunction == null) {
                throw new IllegalStateException(
                        "Handle function must be defined for a No-Return CommandHandler");
            }
            return new NoReturnCommandHandlerImpl<>(
                    CommandHandlerBuilder.this.handleFunction,
                    getCommandType(),
                    CommandHandlerBuilder.this.priority,
                    CommandHandlerBuilder.this.concurrencyLevel);
        }
    }

    final class ReturnBuilderSteps
            implements ResponseHandleFunctionStep<T, R>, ReturnBuildStep<T, R> {

        /** {@inheritDoc} */
        @Override
        public ReturnBuildStep<T, R> withHandleFunctionResponse(
                Function<T, R> handleFunctionWithResponse) {
            CommandHandlerBuilder.this.handleFunctionWithResponse = handleFunctionWithResponse;
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public ReturnBuildStep<T, R> withPriority(int priority) {
            CommandHandlerBuilder.this.priority = priority;
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public ReturnBuildStep<T, R> withConcurrencyLevel(int concurrencyLevel) {
            CommandHandlerBuilder.this.concurrencyLevel = concurrencyLevel;
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public ReturnCommandHandler<T, R> build() {
            if (CommandHandlerBuilder.this.handleFunctionWithResponse == null) {
                throw new IllegalStateException(
                        "Response handle function must be defined for a Return CommandHandler");
            }
            return new ReturnCommandHandlerImpl<>(
                    CommandHandlerBuilder.this.handleFunctionWithResponse,
                    getCommandType(),
                    CommandHandlerBuilder.this.priority,
                    CommandHandlerBuilder.this.concurrencyLevel);
        }
    }
}

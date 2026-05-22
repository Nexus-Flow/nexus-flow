package net.nexus_flow.core.cqrs.command;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.nexus_flow.core.types.TypeReference;

/**
 * Type-safe, fluent DSL for assembling inline {@link CommandHandler} instances without the {@code
 * new CommandTypeSignature<T,R>() {}} anonymous-subclass ceremony.
 *
 * <p>Two terminal shapes are exposed:
 *
 * <ul>
 * <li>{@code forCommand(C.class).handle(consumer)} — fire-and-forget {@link
 * NoReturnCommandHandler}.
 * <li>{@code forCommand(C.class).returns(R.class).handle(function)} — request/response {@link
 * ReturnCommandHandler}.
 * </ul>
 *
 * <p>For commands whose response is a parameterized type (e.g. {@code List<OrderId>}) a {@link
 * TypeReference} overload is provided on {@link CommandStep#returns(TypeReference)}.
 *
 * <p><strong>Design pattern.</strong> This is a classic <em>fluent step-builder with type
 * witnesses</em>: each step accepts exactly one {@link Class} (or {@link TypeReference}) which both
 * <em>narrows</em> the next step's compile-time type and <em>captures</em> the runtime token used
 * by {@link DefaultCommandBus} for routing. The approach is strictly more ergonomic than the
 * equivalent super-type token form for the common case where {@code T} is a record and {@code R} is
 * a non-parameterized class (the 95% of CQRS).
 *
 * <p>The DSL coexists with — does not replace — the lower-level {@link
 * CommandHandler#builder(CommandTypeSignature)} entry point, which remains the only way to
 * configure {@link CommandHandlerBuilder.BuildStep#withPriority(int)} / {@link
 * CommandHandlerBuilder.BuildStep#withConcurrencyLevel(int)}.
 */
public final class CommandHandlerDsl {

    private CommandHandlerDsl() {
        throw new AssertionError("No instances of CommandHandlerDsl");
    }

    /** Entry point. See {@link CommandHandler#forCommand(Class)} for the canonical call site. */
    static <T extends Record> CommandStep<T> forCommand(Class<T> commandType) {
        Objects.requireNonNull(commandType, "commandType");
        return new CommandStepImpl<>(new TypeReference<>(commandType));
    }

    /**
     * First step after {@code forCommand(...)}. Pick either a terminal {@link #handle(Consumer)}
     * (no-return) or a response-typing step via {@code returns(...)}.
     */
    public sealed interface CommandStep<T extends Record> permits CommandStepImpl {

        /**
         * Builds a fire-and-forget handler.
         *
         * @param handler handler logic to run for each command
         * @return no-return command handler
         */
        NoReturnCommandHandler<T> handle(Consumer<T> handler);

        /**
         * Captures a concrete response type.
         *
         * @param responseType response class token
         * @param <R>          response type
         * @return next DSL step that captures the handler function
         */
        <R> ResponseStep<T, R> returns(Class<R> responseType);

        /**
         * Captures a parameterized response type.
         *
         * @param responseType response type token
         * @param <R>          response type
         * @return next DSL step that captures the handler function
         */
        <R> ResponseStep<T, R> returns(TypeReference<R> responseType);
    }

    /** Terminal step: produces a {@link ReturnCommandHandler}. */
    public sealed interface ResponseStep<T extends Record, R> permits ResponseStepImpl {

        /**
         * Builds a request/response handler.
         *
         * @param handler handler logic to run for each command
         * @return return-value command handler
         */
        ReturnCommandHandler<T, R> handle(Function<T, R> handler);
    }

    // Impls

    private record CommandStepImpl<T extends Record>(TypeReference<T> commandType)
            implements CommandStep<T> {

        /** {@inheritDoc} */
        @Override
        public NoReturnCommandHandler<T> handle(Consumer<T> handler) {
            Objects.requireNonNull(handler, "handler");
            return new CommandHandlerBuilder.NoReturnCommandHandlerImpl<>(
                    handler, commandType, /* priority */ 0, /* concurrencyLevel */ 0);
        }

        /** {@inheritDoc} */
        @Override
        public <R> ResponseStep<T, R> returns(Class<R> responseType) {
            Objects.requireNonNull(responseType, "responseType");
            return new ResponseStepImpl<>(commandType);
        }

        /** {@inheritDoc} */
        @Override
        public <R> ResponseStep<T, R> returns(TypeReference<R> responseType) {
            Objects.requireNonNull(responseType, "responseType");
            return new ResponseStepImpl<>(commandType);
        }
    }

    private record ResponseStepImpl<T extends Record, R>(TypeReference<T> commandType)
            implements ResponseStep<T, R> {

        /** {@inheritDoc} */
        @Override
        public ReturnCommandHandler<T, R> handle(Function<T, R> handler) {
            Objects.requireNonNull(handler, "handler");
            return new CommandHandlerBuilder.ReturnCommandHandlerImpl<>(
                    handler, commandType, /* priority */ 0, /* concurrencyLevel */ 0);
        }
    }
}

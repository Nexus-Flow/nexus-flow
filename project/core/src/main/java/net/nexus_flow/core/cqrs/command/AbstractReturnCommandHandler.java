package net.nexus_flow.core.cqrs.command;

import java.util.concurrent.Callable;
import java.util.function.BiFunction;

/**
 * Convenience base for {@link ReturnCommandHandler} implementations that carry their own state.
 * Captures the {@code <T, R>} type parameters via the inherited {@link CommandTypeSignature}
 * super-type token (instantiated as an anonymous subclass):
 *
 * <p>
 *
 * {@snippet :
 * var handler = new AbstractReturnCommandHandler<CreateOrder, OrderId>() {
 *     &#64;Override
 *     protected OrderId handle(CreateOrder command) {
 *         return new OrderId(command.id());
 *     }
 * };
 * }
 *
 * <p>For inline lambda-style handlers prefer {@link CommandHandler#forCommand(Class)}.
 */
public abstract non-sealed class AbstractReturnCommandHandler<T extends Record, R> extends CommandTypeSignature<T, R> implements
        ReturnCommandHandler<T, R> {

    private final BiFunction<AbstractReturnCommandHandler<T, R>, T, R> commandHandlerRef =
            AbstractReturnCommandHandler::handle;

    /**
     * Convenience base for {@link ReturnCommandHandler} implementations that carry their own state.
     * Captures the {@code <T, R>} type parameters via the inherited {@link CommandTypeSignature}
     * super-type token (instantiated as an anonymous subclass):
     *
     * <p>
     *
     * {@snippet :
     * var handler = new AbstractReturnCommandHandler<CreateOrder, OrderId>() {
     *     &#64;Override
     *     protected OrderId handle(CreateOrder command) {
     *         return new OrderId(command.id());
     *     }
     * };
     * }
     *
     * <p>For inline lambda-style handlers prefer {@link CommandHandler#forCommand(Class)}.
     */
    protected abstract R handle(T command);

    /**
     * Convenience base for {@link ReturnCommandHandler} implementations that carry their own state.
     * Captures the {@code <T, R>} type parameters via the inherited {@link CommandTypeSignature}
     * super-type token (instantiated as an anonymous subclass):
     *
     * <p>
     *
     * {@snippet :
     * var handler = new AbstractReturnCommandHandler<CreateOrder, OrderId>() {
     *     &#64;Override
     *     protected OrderId handle(CreateOrder command) {
     *         return new OrderId(command.id());
     *     }
     * };
     * }
     *
     * <p>For inline lambda-style handlers prefer {@link CommandHandler#forCommand(Class)}.
     */
    @Override
    public ReturnCommandHandlerInternal<T, R> getInternal() {
        return getInnerHandler();
    }

    AbstractReturnCommandHandlerInner getInnerHandler() {
        return new AbstractReturnCommandHandlerInner();
    }

    private final class AbstractReturnCommandHandlerInner
            implements ReturnCommandHandlerInternal<T, R> {
        @Override
        public Callable<R> handleAndReturn(T command) {
            R result = commandHandlerRef.apply(AbstractReturnCommandHandler.this, command);
            return () -> result;
        }
    }
}

package net.nexus_flow.core.cqrs.command;

import java.util.function.BiConsumer;

/**
 * Convenience base for {@link NoReturnCommandHandler} implementations that carry their own state.
 * See {@link AbstractReturnCommandHandler} for the rationale and recommended alternative ({@link
 * CommandHandler#forCommand(Class)}).
 *
 * <p>
 *
 * {@snippet :
 * var handler = new AbstractNoReturnCommandHandler<ShipOrder>() {
 *     &#64;Override
 *     protected void handle(ShipOrder command) {
 *         shipping.ship(command.orderId());
 *     }
 * };
 * }
 */
public abstract non-sealed class AbstractNoReturnCommandHandler<T extends Record> extends CommandTypeSignature<T, Void> implements
        NoReturnCommandHandler<T> {

    private final BiConsumer<AbstractNoReturnCommandHandler<T>, T> commandHandlerRef =
            AbstractNoReturnCommandHandler::handle;

    /**
     * Handles the supplied command payload.
     *
     * @param command command payload to process
     */
    protected abstract void handle(T command);

    /** {@inheritDoc} */
    @Override
    public NoReturnCommandHandlerInternal<T> getInternal() {
        return getInnerHandler();
    }

    AbstractNoReturnCommandHandlerInner getInnerHandler() {
        return new AbstractNoReturnCommandHandlerInner();
    }

    private final class AbstractNoReturnCommandHandlerInner
            implements NoReturnCommandHandlerInternal<T> {
        @Override
        public Runnable handle(T command) {
            return () -> commandHandlerRef.accept(AbstractNoReturnCommandHandler.this, command);
        }
    }
}

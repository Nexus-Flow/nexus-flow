package net.nexus_flow.core.cqrs.command;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public abstract non-sealed class AbstractNoReturnCommandHandler<T extends Record> extends CommandTypeSignature<T, Void> implements NoReturnCommandHandler<T> {

    private final BiConsumer<AbstractNoReturnCommandHandler<T>, T> commandHandlerRef = AbstractNoReturnCommandHandler::handle;

    public static <T extends Record> NoReturnCommandHandler<T> of(Consumer<T> handle) {
        Objects.requireNonNull(handle);
        return new AbstractNoReturnCommandHandler<>() {
            @Override
            protected void handle(T command) {
                handle.accept(command);
            }
        };
    }

    protected abstract void handle(T command);

    AbstractNoReturnCommandHandlerInner getInnerHandler() {
        return new AbstractNoReturnCommandHandlerInner();
    }

    private class AbstractNoReturnCommandHandlerInner implements NoReturnCommandHandlerInternal<T> {
        @Override
        public Runnable handle(T command) {
//            if (!getClass().getEnclosingClass().equals(AbstractNoReturnCommandHandler.this.getClass())) {
//                throw new IllegalStateException("Direct invocation of the handle method is not allowed.");
//            }
            return () -> commandHandlerRef.accept(AbstractNoReturnCommandHandler.this, command);
        }
    }
}
package net.nexus_flow.core.cqrs.command;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;

public non-sealed abstract class AbstractReturnCommandHandler<T extends Record, R> extends CommandTypeSignature<T, R> implements ReturnCommandHandler<T, R> {

    private final BiFunction<AbstractReturnCommandHandler<T, R>, T, R> commandHandlerRef = AbstractReturnCommandHandler::handle;

    public static <T extends Record, R> ReturnCommandHandler<T, R> of(Function<T, R> handle) {
        Objects.requireNonNull(handle);
        return new AbstractReturnCommandHandler<>() {
            @Override
            protected R handle(T command) {
                return handle.apply(command);
            }
        };
    }

    protected abstract R handle(T command);

    AbstractReturnCommandHandlerInner getInnerHandler() {
        return new AbstractReturnCommandHandlerInner();
    }

    private class AbstractReturnCommandHandlerInner implements ReturnCommandHandlerInternal<T, R> {
        @Override
        public Callable<R> handleAndReturn(T command) {
//            if (!getClass().getEnclosingClass().equals(AbstractReturnCommandHandler.this.getClass())) {
//                throw new IllegalStateException("Direct invocation of the handle method is not allowed.");
//            }
            R result = commandHandlerRef.apply(AbstractReturnCommandHandler.this, command);
            return () -> result;
        }
    }
}
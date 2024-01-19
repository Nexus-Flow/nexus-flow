package org.nexusflow.core.cqrs.query;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;

public non-sealed abstract class AbstractQueryHandler<T extends Record, R> extends QueryTypeSignature<T, R> implements QueryHandler<T, R> {

    private final BiFunction<AbstractQueryHandler<T, R>, T, R> queryHandlerRef = AbstractQueryHandler::handle;

    public static <T extends Record, R> QueryHandler<T, R> of(Function<T, R> handle) {
        Objects.requireNonNull(handle);
        return new AbstractQueryHandler<>() {
            @Override
            public R handle(T query) {
                return handle.apply(query);
            }
        };
    }

    public abstract R handle(T query);

    Callable<R> getInnerHandler(T query) {
        R result = queryHandlerRef.apply(AbstractQueryHandler.this, query);
        return () -> result;
    }

}
package org.nexusflow.core.cqrs.query;

import org.nexusflow.core.cqrs.query.exceptions.QueryHandlerExecutionError;
import org.nexusflow.core.cqrs.query.exceptions.QueryNotRegisteredError;

import java.util.concurrent.Callable;

non-sealed class DefaultQueryBus implements QueryBus {

    private final QueryConsumerRegistry consumerRegistry = QueryConsumerRegistry.getInstance();


    @Override
    public <T extends Record, R> void register(QueryHandler<T, R> handler) {
        if (handler instanceof AbstractQueryHandler<T, R> handler1) {
            consumerRegistry.createPublisher(handler1.getQueryType(), handler);
        }
    }

    @Override
    public <T extends Record, R> void unregister(QueryHandler<T, R> handler) {
        if (handler instanceof AbstractQueryHandler<T, R> handler1) {
            consumerRegistry.clearPublisher(handler1.getQueryType());
        }
    }

    @Override
    public <T extends Record, R> R ask(Query<T> query) throws QueryHandlerExecutionError {
        QueryHandler<T, R> handler = consumerRegistry.getPublisher(query.getType());
        if (handler instanceof AbstractQueryHandler<T,R> handler1) {
            Callable<R> response = handler1.getInnerHandler(query.getBody());
            try {
                return response.call();
            } catch (Exception e) {
                throw new QueryHandlerExecutionError(e);
            }
        }
        throw new QueryNotRegisteredError(query);
    }
}

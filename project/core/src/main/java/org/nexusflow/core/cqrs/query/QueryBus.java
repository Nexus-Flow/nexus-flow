package org.nexusflow.core.cqrs.query;

import org.nexusflow.core.cqrs.query.exceptions.QueryHandlerExecutionError;

public sealed interface QueryBus permits DefaultQueryBus {

    static QueryBus getInstance() {
        return QueryBusFactory.getInstance();
    }

    <T extends Record, R> void register(QueryHandler<T, R> handler);

    <T extends Record, R> void unregister(QueryHandler<T, R> handler);

    <T extends Record, R> R ask(Query<T> query) throws QueryHandlerExecutionError;

    class QueryBusFactory {
        private static volatile QueryBus instance;

        private QueryBusFactory() {
        }

        public static QueryBus getInstance() {
            if (instance == null) {
                synchronized (QueryBusFactory.class) {
                    if (instance == null) {
                        instance = new DefaultQueryBus();
                    }
                }
            }
            return instance;
        }
    }
}
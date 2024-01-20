package org.nexusflow.core.cqrs.query;

import org.nexusflow.core.cqrs.reflection.TypeReference;

interface QueryConsumerRegistry {

    static QueryConsumerRegistry getInstance() {
        return QueryConsumerRegistryFactory.getInstance();
    }

    <T extends Record, R> void createPublisher(TypeReference<T> typeReference, QueryHandler<T, R> handler);

    <T extends Record> void clearPublisher(TypeReference<T> typeReference);

    <T extends Record, R> QueryHandler<T, R> getPublisher(TypeReference<T> typeReference);

}

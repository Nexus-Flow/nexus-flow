package net.nexus_flow.core.cqrs.query;

import net.nexus_flow.core.cqrs.reflection.TypeReference;

interface QueryConsumerRegistry {

    static QueryConsumerRegistry getInstance() {
        return QueryConsumerRegistryFactory.getInstance();
    }

    <T extends Record, R> void createPublisher(TypeReference<T> typeReference, QueryHandler<T, R> handler);

    <T extends Record> void clearPublisher(TypeReference<T> typeReference);

    <T extends Record, R> QueryHandler<T, R> getPublisher(TypeReference<T> typeReference);

}

package net.nexus_flow.core.cqrs.query;

import net.nexus_flow.core.cqrs.reflection.TypeReference;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class DefaultQueryConsumerRegistry implements QueryConsumerRegistry {
    private final Map<TypeReference<?>, QueryHandler<?, ?>> publisherMap = new ConcurrentHashMap<>();

    @Override
    public <T extends Record, R> void createPublisher(TypeReference<T> typeReference, QueryHandler<T, R> handler) {
        if (handler instanceof AbstractQueryHandler<T,R> handler1) {
            publisherMap.putIfAbsent(typeReference, handler1);
        }
    }

    @Override
    public <T extends Record> void clearPublisher(TypeReference<T> typeReference) {
        publisherMap.remove(typeReference);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record, R> QueryHandler<T, R> getPublisher(TypeReference<T> typeReference) {
        return (QueryHandler<T, R>) publisherMap.get(typeReference);
    }
}

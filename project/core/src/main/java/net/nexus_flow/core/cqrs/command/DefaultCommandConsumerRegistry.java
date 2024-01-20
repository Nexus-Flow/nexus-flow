package net.nexus_flow.core.cqrs.command;

import net.nexus_flow.core.cqrs.reflection.TypeReference;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class DefaultCommandConsumerRegistry implements CommandConsumerRegistry {
    private final Map<TypeReference<?>, NoReturnHandlerExecutor<?>> publisherMap = new ConcurrentHashMap<>();
    private final Map<TypeReference<?>, ReturnHandlerExecutor<?, ?>> returnPublisherMap = new ConcurrentHashMap<>();

    @Override
    public <T extends Record, H extends NoReturnCommandHandler<T>> void createPublisher(TypeReference<T> typeReference, H handler) {
        publisherMap.computeIfAbsent(typeReference, _ -> new NoReturnHandlerExecutor<>(handler));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record> NoReturnHandlerExecutor<T> getNoReturnPublisher(TypeReference<T> typeReference) {
        return (NoReturnHandlerExecutor<T>) publisherMap.get(typeReference);
    }

    @Override
    public <T extends Record> void clearNoReturnPublisher(TypeReference<T> typeReference) {
        NoReturnHandlerExecutor<?> publisher = publisherMap.get(typeReference);
        publisher.close();
        publisherMap.remove(typeReference);
    }

    @Override
    public <T extends Record, R, H extends ReturnCommandHandler<T, R>> void createPublisher(TypeReference<T> typeReference, H handler) {
        returnPublisherMap.computeIfAbsent(typeReference, _ -> new ReturnHandlerExecutor<>(handler));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record, R> ReturnHandlerExecutor<T, R> getReturnPublisher(TypeReference<T> typeReference) {
        return (ReturnHandlerExecutor<T, R>) returnPublisherMap.get(typeReference);
    }

    @Override
    public <T extends Record, R> void clearReturnPublisher(TypeReference<T> typeReference) {
        ReturnHandlerExecutor<?, ?> publisher = returnPublisherMap.get(typeReference);
        publisher.close();
        returnPublisherMap.remove(typeReference);
    }

}

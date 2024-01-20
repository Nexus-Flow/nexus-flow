package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.cqrs.reflection.TypeReference;
import org.nexusflow.core.ddd.DomainEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class DefaultEventPublisherRegistry implements EventPublisherRegistry {
    private final Map<TypeReference<?>, EventPublisher<?>> publisherMap = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public <E extends DomainEvent> EventPublisher<E> getOrCreatePublisher(TypeReference<E> eventType) {
        return (EventPublisher<E>) publisherMap.computeIfAbsent(eventType, _ -> new DefaultEventPublisher<>());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends DomainEvent> EventPublisher<E> getPublisher(TypeReference<E> eventType) {
        return (EventPublisher<E>) publisherMap.get(eventType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends DomainEvent> void clearPublisher(TypeReference<E> eventType) {
        EventPublisher<E> publisher = (EventPublisher<E>) publisherMap.get(eventType);
        publisher.close();
        publisherMap.remove(eventType);
    }
}

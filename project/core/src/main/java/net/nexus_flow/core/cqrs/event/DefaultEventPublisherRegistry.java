package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.cqrs.reflection.TypeReference;
import net.nexus_flow.core.ddd.DomainEvent;

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

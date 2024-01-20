package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.cqrs.reflection.TypeReference;
import net.nexus_flow.core.ddd.DomainEvent;

interface EventPublisherRegistry {

    static EventPublisherRegistry getInstance() {
        return EventPublisherRegistryFactory.getInstance();
    }

    <E extends DomainEvent> EventPublisher<E> getOrCreatePublisher(TypeReference<E> eventType);

    <E extends DomainEvent> EventPublisher<E> getPublisher(TypeReference<E> eventType);

    <E extends DomainEvent> void clearPublisher(TypeReference<E> eventType);

}

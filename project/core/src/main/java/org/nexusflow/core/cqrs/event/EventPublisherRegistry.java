package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.cqrs.reflection.TypeReference;
import org.nexusflow.core.ddd.DomainEvent;

interface EventPublisherRegistry {

    static EventPublisherRegistry getInstance() {
        return EventPublisherRegistryFactory.getInstance();
    }

    <E extends DomainEvent> EventPublisher<E> getOrCreatePublisher(TypeReference<E> eventType);

    <E extends DomainEvent> EventPublisher<E> getPublisher(TypeReference<E> eventType);

    <E extends DomainEvent> void clearPublisher(TypeReference<E> eventType);

}

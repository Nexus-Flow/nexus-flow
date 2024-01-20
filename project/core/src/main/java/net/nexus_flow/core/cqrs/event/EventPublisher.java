package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;

interface EventPublisher<E extends DomainEvent> {
    void publish(E event, boolean isSaga);

    void removeListener(DomainEventListener<E> listener);

    void addListener(DomainEventListener<E> listener);

    boolean isListenerListEmpty();

    void close();
}
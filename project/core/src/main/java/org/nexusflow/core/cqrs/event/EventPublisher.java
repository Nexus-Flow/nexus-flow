package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.ddd.DomainEvent;

public interface EventPublisher<E extends DomainEvent> {
    void publish(E event, boolean isSaga);

    void removeListener(DomainEventListener<E> listener);

    void addListener(DomainEventListener<E> listener);

    boolean isListenerListEmpty();

    void close();
}
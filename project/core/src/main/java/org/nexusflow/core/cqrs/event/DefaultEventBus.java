package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.cqrs.reflection.TypeReference;
import org.nexusflow.core.ddd.DomainEvent;

public non-sealed class DefaultEventBus implements EventBus {

    private final EventPublisherRegistry publisherRegistry = EventPublisherRegistry.getInstance();

    @Override
    public <E extends DomainEvent> void register(DomainEventListener<E> listener) {
        if (listener instanceof AbstractDomainEventListener<E> abstractDomainEventListener) {
            EventPublisher<E> publisher = publisherRegistry.getOrCreatePublisher(abstractDomainEventListener.getEventType());
            publisher.addListener(listener);
        }
    }

    @Override
    public <E extends DomainEvent> void unregister(DomainEventListener<E> listener) {
        if (listener instanceof AbstractDomainEventListener<E> abstractDomainEventListener) {
            publisherRegistry.clearPublisher(abstractDomainEventListener.getEventType());
        }
    }

    @Override
    public <E extends DomainEvent> void dispatch(E event, boolean isSaga) {
        TypeReference<E> eventType = new TypeReference<>(event.getClass());
        EventPublisher<E> publisher = publisherRegistry.getOrCreatePublisher(eventType);
        publisher.publish(event, isSaga);
    }
}

package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.cqrs.reflection.TypeReference;
import net.nexus_flow.core.ddd.DomainEvent;

non-sealed class DefaultEventBus implements EventBus {

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

package org.nexusflow.core.cqrs.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EventPublisherFactory {
    private final Map<DomainEventListener<?>, EventPublisher<?>> publishers = new ConcurrentHashMap<>();

    public EventPublisher<?> getPublisher(DomainEventListener<?> listener) {
        return publishers.computeIfAbsent(listener, _ -> new DefaultEventPublisher<>());
    }
}

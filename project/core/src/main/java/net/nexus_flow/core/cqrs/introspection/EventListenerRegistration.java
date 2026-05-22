package net.nexus_flow.core.cqrs.introspection;

import java.util.Objects;
import net.nexus_flow.core.cqrs.event.DomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.types.TypeReference;

/**
 * Opaque registration token for domain-event listeners discovered by IoC adapters.
 *
 * <p>Spring runtime reflection, Quarkus build-time indexing, Micronaut annotation processors, or
 * similar integrations can scan beans for listener methods, call {@code
 * DomainEventListener.fromMethod(...)}, inspect the event routing token, and register on an {@link
 * EventBus} without exposing wildcard-typed listener references to integration code.
 */
public final class EventListenerRegistration {
    private final DomainEventListener<?> listener;
    private final TypeReference<?>       eventType;

    EventListenerRegistration(DomainEventListener<?> listener, TypeReference<?> eventType) {
        this.listener  = Objects.requireNonNull(listener, "listener");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
    }

    /**
     * Creates a token for a domain-event listener.
     *
     * @param <E>       event type
     * @param listener  concrete domain-event listener
     * @param eventType exact event routing key
     * @return immutable registration token
     */
    public static <E extends DomainEvent> EventListenerRegistration of(
            DomainEventListener<E> listener, TypeReference<E> eventType) {
        return new EventListenerRegistration(listener, eventType);
    }

    /**
     * Returns the domain-event type used as the event-bus routing key.
     *
     * @return event routing type token
     */
    @SuppressWarnings(
        "java:S1452") // Type token (akin to Class<?>); routing key, not a generic container.
    public TypeReference<?> eventType() {
        return eventType;
    }

    /**
     * Registers the wrapped listener on the given event bus.
     *
     * @param bus target event bus
     * @throws NullPointerException if {@code bus} is {@code null}
     */
    public void registerOn(EventBus bus) {
        Objects.requireNonNull(bus, "bus");
        registerTyped(bus, listener);
    }

    @SuppressWarnings("unchecked")
    private static <E extends DomainEvent> void registerTyped(
            EventBus bus, DomainEventListener<?> listener) {
        bus.register((DomainEventListener<E>) listener);
    }
}

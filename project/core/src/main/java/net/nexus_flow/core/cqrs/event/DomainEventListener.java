package net.nexus_flow.core.cqrs.event;

import java.lang.reflect.Method;
import java.util.Objects;
import net.nexus_flow.core.cqrs.introspection.EventListenerRegistration;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * Public root of the domain-event-listener hierarchy. The actual {@code handle(E)} contract is
 * inherited from the package-private {@link EventListener} super-interface so external callers can
 * only implement listeners by subclassing {@link AbstractDomainEventListener} or using the
 * ergonomic DSL below.
 */
public sealed interface DomainEventListener<E extends DomainEvent>
        extends EventListener<E, DomainEventListener<E>> permits AbstractDomainEventListener {

    /**
     * <strong>Preferred</strong> ergonomic entry point for assembling an inline domain-event
     * listener.
     *
     * <p>
     *
     * {@snippet :
     * var listener = DomainEventListener.forEvent(OrderShipped.class)
     *         .handle(event -> audit.record(event));
     * eventBus.register(listener);
     * }
     */
    static <E extends DomainEvent> DomainEventListenerDsl.EventStep<E> forEvent(Class<E> eventType) {
        return DomainEventListenerDsl.forEvent(eventType);
    }

    /**
     * Build a listener from an already-discovered Java method.
     *
     * <p>This is the public SPI bridge for IoC integrations that scan container beans for annotated
     * methods. The method must declare exactly one {@link DomainEvent} parameter. The returned token
     * exposes routing metadata and performs bus registration.
     */
    static EventListenerRegistration fromMethod(Object target, Method method) {
        return fromMethod(target, method, EventListenerOptions.defaults());
    }

    /** Build a method-backed listener with explicit listener options. */
    static EventListenerRegistration fromMethod(
            Object target, Method method, EventListenerOptions<?> options) {
        Objects.requireNonNull(options, "options");
        return DomainEventListenerMethodAdapter.fromMethod(target, method, options);
    }
}

package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.types.TypeReference;

/**
 * Package-private marker for {@link DomainEventListener} implementations that carry their {@link
 * TypeReference} at runtime even when they are not instantiated via the anonymous-subclass pattern.
 *
 * <p>Used by {@link DefaultEventBus} to extract the routing key from DSL-produced listeners ({@link
 * DomainEventListenerDsl.InlineEventListener}) without requiring them to be anonymous subclasses.
 *
 * @param <E> the domain-event type
 */
@FunctionalInterface
interface EventTypeAware<E extends DomainEvent> {
    /**
     * @return the {@link TypeReference} for the event type this listener accepts; used by {@link
     *         DefaultEventBus} to route incoming events to the right listener(s).
     */
    TypeReference<E> getEventType();
}

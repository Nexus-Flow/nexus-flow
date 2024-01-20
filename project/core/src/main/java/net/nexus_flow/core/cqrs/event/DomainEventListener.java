package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;

public sealed interface DomainEventListener<E extends DomainEvent> extends EventListener<E, DomainEventListener<E>> permits AbstractDomainEventListener {
    // Aquí no se expone el método `handle`.
}

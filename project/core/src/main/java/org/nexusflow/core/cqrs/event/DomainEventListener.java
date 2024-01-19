package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.ddd.DomainEvent;

public sealed interface DomainEventListener<E extends DomainEvent> extends EventListener<E, DomainEventListener<E>> permits AbstractDomainEventListener {
    // Aquí no se expone el método `handle`.
}

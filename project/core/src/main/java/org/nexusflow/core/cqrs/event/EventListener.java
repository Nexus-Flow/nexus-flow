package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.ddd.DomainEvent;

sealed interface EventListener<E extends DomainEvent, H extends EventListener<E, H>> permits DomainEventListener {

    void handle(E event) throws Exception;

    default int order() {
        return 0;
    }

}
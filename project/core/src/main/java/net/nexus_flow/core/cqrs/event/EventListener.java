package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;

sealed interface EventListener<E extends DomainEvent, H extends EventListener<E, H>> permits DomainEventListener {

    void handle(E event) throws Exception;

    default int order() {
        return 0;
    }

}
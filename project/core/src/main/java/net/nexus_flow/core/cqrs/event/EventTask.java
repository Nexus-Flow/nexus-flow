package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;

record EventTask<E extends DomainEvent>(E event, DomainEventListener<E> listener, boolean isSaga) implements Runnable {
    @Override
    public void run() {
        try {
            listener.handle(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
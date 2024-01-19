package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.ddd.DomainEvent;

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
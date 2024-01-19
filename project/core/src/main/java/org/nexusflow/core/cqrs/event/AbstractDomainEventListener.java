package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.ddd.DomainEvent;

import java.util.Objects;
import java.util.function.Consumer;

public non-sealed abstract class AbstractDomainEventListener<E extends DomainEvent> extends EventTypeSignature<E> implements DomainEventListener<E> {

    public static <E extends DomainEvent> DomainEventListener<E> of(Consumer<E> handle) {
        Objects.requireNonNull(handle);
        return new AbstractDomainEventListener<>() {
            @Override
            public void handle(E event) {
                handle.accept(event);
            }
        };
    }

    public abstract void handle(E event);

}
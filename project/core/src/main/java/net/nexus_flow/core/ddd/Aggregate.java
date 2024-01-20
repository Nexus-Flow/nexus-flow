package net.nexus_flow.core.ddd;

import net.nexus_flow.core.cqrs.event.DomainEventContext;
import net.nexus_flow.core.cqrs.event.DomainEventContextHolder;

import java.util.List;

public abstract non-sealed class Aggregate implements AggregateRoot {
    private final DomainEventContext eventContext;

    protected Aggregate() {
        this.eventContext = DomainEventContextHolder.getContext();
    }

    @Override
    public final void recordEvent(List<DomainEvent> domainEvents) {
        domainEvents.forEach(eventContext::recordEvent);
    }

    @Override
    public final void recordEvent(DomainEvent domainEvent) {
        eventContext.recordEvent(domainEvent);
    }

    @Override
    public final List<DomainEvent> getEvents() {
        return eventContext.getEvents();
    }

}

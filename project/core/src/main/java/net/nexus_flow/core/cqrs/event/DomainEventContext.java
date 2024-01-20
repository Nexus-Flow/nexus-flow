package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;

import java.util.List;

public interface DomainEventContext {
    void recordEvent(DomainEvent event);

    List<DomainEvent> getEvents();

    void clearEvents();

    boolean hasEventsRecorded();

    void resetEventsRecorded();
}

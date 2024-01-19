package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.ddd.DomainEvent;

import java.util.List;

public interface DomainEventContext {
    void recordEvent(DomainEvent event);

    List<DomainEvent> getEvents();

    void clearEvents();

    boolean hasEventsRecorded();

    void resetEventsRecorded();
}

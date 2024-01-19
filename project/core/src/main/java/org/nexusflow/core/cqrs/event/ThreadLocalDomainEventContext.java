package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.ddd.DomainEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ThreadLocalDomainEventContext implements DomainEventContext {
    private static final ThreadLocal<List<DomainEvent>> DOMAIN_EVENTS = ThreadLocal.withInitial(ArrayList::new);
    private final AtomicBoolean eventsRecorded = new AtomicBoolean(false);

    @Override
    public void recordEvent(DomainEvent event) {
        DOMAIN_EVENTS.get().add(event);
        eventsRecorded.set(true);
    }

    @Override
    public List<DomainEvent> getEvents() {
        return DOMAIN_EVENTS.get();
    }

    @Override
    public void clearEvents() {
        DOMAIN_EVENTS.remove();
        resetEventsRecorded();
    }

    @Override
    public boolean hasEventsRecorded() {
        return eventsRecorded.get();
    }

    @Override
    public void resetEventsRecorded() {
        eventsRecorded.set(false);
    }
}

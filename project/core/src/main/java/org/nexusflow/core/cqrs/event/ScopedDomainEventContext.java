package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.ddd.DomainEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScopedDomainEventContext implements DomainEventContext {
    private static final ScopedValue<List<DomainEvent>> DOMAIN_EVENTS = ScopedValue.newInstance();
    private final AtomicBoolean eventsRecorded = new AtomicBoolean(false);

    @Override
    public void recordEvent(DomainEvent event) {
        List<DomainEvent> events = DOMAIN_EVENTS.get();
        if (events == null) {
            events = new ArrayList<>();
            ScopedValue.runWhere(DOMAIN_EVENTS, events, () -> {});
        }
        events.add(event);
        eventsRecorded.set(true);
    }

    @Override
    public List<DomainEvent> getEvents() {
        return DOMAIN_EVENTS.get();
    }

    @Override
    public void clearEvents() {
        ScopedValue.runWhere(DOMAIN_EVENTS, null, () -> {});
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

    public ScopedValue<List<DomainEvent>> getScopedValue() {
        return DOMAIN_EVENTS;
    }

}

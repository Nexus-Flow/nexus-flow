package com.nexus_flow.core.sagas.domain.value_objects.saga_type;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import com.nexus_flow.core.sagas.domain.SagaDomainEvent;

import java.util.*;

public class OtherInvolvedEventsClasses {

    private Set<Class<? extends SagaDomainEvent>> events = new HashSet<>();

    private OtherInvolvedEventsClasses() {
    }

    public OtherInvolvedEventsClasses(List<Class<? extends SagaDomainEvent>> events) {
        checkNotNull(events);
        this.events = new HashSet<>(events);
    }

    public OtherInvolvedEventsClasses(Set<Class<? extends SagaDomainEvent>> events) {
        checkNotNull(events);
        this.events = events;
    }

    private void checkNotNull(Collection<Class<? extends SagaDomainEvent>> value) {
        if (value == null) {
            throw new WrongFormat(this.getClass());
        }
    }

    public List<Class<? extends SagaDomainEvent>> asList() {
        return new ArrayList<>(events);
    }

    public boolean checkIsInvolvedEvent(SagaDomainEvent sagaDomainEvent) {
        return events.contains(sagaDomainEvent.getClass());
    }

    public Set<Class<? extends SagaDomainEvent>> getEvents() {
        return events;
    }

    @Override
    public int hashCode() {
        return Objects.hash(events);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OtherInvolvedEventsClasses that = (OtherInvolvedEventsClasses) o;
        return Objects.equals(events, that.events);
    }
}

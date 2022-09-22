package com.nexus_flow.core.sagas.domain.value_objects.saga_member;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import com.nexus_flow.core.messaging.domain.DomainEvent;

import java.util.Objects;

public class EventTriggerSagaMember implements SagaMemberPayload {

    private DomainEvent value;

    public EventTriggerSagaMember() {
    }

    public EventTriggerSagaMember(DomainEvent value) {
        checkNotNull(value);
        this.value = value;
    }

    private void checkNotNull(DomainEvent event) {
        if (event == null) {
            throw new WrongFormat(EventTriggerSagaMember.class);
        }
    }

    public DomainEvent getValue() {
        return value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EventTriggerSagaMember that = (EventTriggerSagaMember) o;
        return Objects.equals(value, that.value);
    }
}

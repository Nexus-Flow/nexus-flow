package com.nexus_flow.core.sagas.domain.value_objects.saga_member;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.sagas.domain.SagaDomainEvent;

import java.util.Objects;

public class EventNotTriggerSagaMember implements SagaMemberPayload {

    private SagaDomainEvent value;

    public EventNotTriggerSagaMember() {
    }

    public EventNotTriggerSagaMember(SagaDomainEvent value) {
        checkNotNull(value);
        this.value = value;
    }

    private void checkNotNull(DomainEvent event) {
        if (event == null) {
            throw new WrongFormat(EventNotTriggerSagaMember.class);
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
        EventNotTriggerSagaMember that = (EventNotTriggerSagaMember) o;
        return Objects.equals(value, that.value);
    }
}

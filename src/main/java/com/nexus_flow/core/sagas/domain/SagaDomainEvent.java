package com.nexus_flow.core.sagas.domain;


import com.nexus_flow.core.messaging.domain.DomainEvent;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

public abstract class SagaDomainEvent extends DomainEvent {

    protected final String sagaTriggerId;
    protected final String sagaTriggeredOn;


    protected SagaDomainEvent() {
        super(null);
        this.sagaTriggerId   = null;
        this.sagaTriggeredOn = null;
    }

    protected SagaDomainEvent(String aggregateId, String sagaTriggerId, String sagaTriggeredOn) {
        super(aggregateId);
        this.sagaTriggerId   = sagaTriggerId;
        this.sagaTriggeredOn = sagaTriggeredOn;
    }
    
    protected SagaDomainEvent(String aggregateId) {
        super(aggregateId);
        this.sagaTriggerId   = null;
        this.sagaTriggeredOn = null;
    }

    protected SagaDomainEvent(String aggregateId,
                              String eventId,
                              String occurredOn,
                              Integer timesTryingToPublish,

                              String sagaTriggerId,
                              String sagaTriggeredOn) {
        super(aggregateId, eventId, occurredOn, timesTryingToPublish);
        this.sagaTriggerId   = sagaTriggerId;
        this.sagaTriggeredOn = sagaTriggeredOn;
    }


    @Override
    public Map<String, Serializable> toPrimitives() {
        return toPrimitivesAll();
    }


    public String getSagaTriggerId() {
        return sagaTriggerId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sagaTriggerId, sagaTriggeredOn);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaDomainEvent that = (SagaDomainEvent) o;
        return Objects.equals(sagaTriggerId, that.sagaTriggerId) &&
                Objects.equals(sagaTriggeredOn, that.sagaTriggeredOn);
    }

    public String getSagaTriggeredOn() {

        return sagaTriggeredOn;
    }

}

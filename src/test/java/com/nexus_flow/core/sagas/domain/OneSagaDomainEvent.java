package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.messaging.domain.DomainEvent;

import java.io.Serializable;
import java.util.Map;

public class OneSagaDomainEvent extends SagaDomainEvent {

    public OneSagaDomainEvent(String aggregateId,
                              String eventId,
                              String occurredOn,
                              Integer timesTryingToPublish,
                              String sagaTriggerId,
                              String sagaTriggeredOn) {
        super(aggregateId, eventId, occurredOn, timesTryingToPublish, sagaTriggerId, sagaTriggeredOn);
    }

    public OneSagaDomainEvent(String aggregateId) {
        super(aggregateId);
    }

    public OneSagaDomainEvent() {
        super();
    }

    @Override
    public String getEventName() {
        return "One SagaMember Domain Event";
    }

    @Override
    public DomainEvent fromPrimitives(String aggregateId,
                                      String eventId,
                                      String occurredOn,
                                      Integer timesTryingToPublish,
                                      Map<String, Serializable> body) {
        return null;
    }

}

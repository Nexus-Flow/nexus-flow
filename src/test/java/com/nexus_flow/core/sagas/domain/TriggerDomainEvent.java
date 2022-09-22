package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.messaging.domain.DomainEvent;

import java.io.Serializable;
import java.util.Map;

public class TriggerDomainEvent extends DomainEvent {

    public TriggerDomainEvent(String aggregateId) {
        super(aggregateId);
    }

    public TriggerDomainEvent() {
        super();
    }

    @Override
    public String getEventName() {
        return "Trigger Domain Event";
    }

    @Override
    public Map<String, Serializable> toPrimitives() {
        return null;
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

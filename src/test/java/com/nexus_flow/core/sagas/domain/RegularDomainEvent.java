package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.messaging.domain.DomainEvent;

import java.io.Serializable;
import java.util.Map;

public class RegularDomainEvent extends DomainEvent {

    public RegularDomainEvent(String aggregateId) {
        super(aggregateId);
    }

    public RegularDomainEvent() {
        super();
    }

    @Override
    public String getEventName() {
        return "Regular Domain Event";
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

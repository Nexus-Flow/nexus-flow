package com.nexus_flow.core.messaging.domain;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.nexus_flow.core.ddd.Utils;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public abstract class DomainEvent {
    private String  aggregateId;
    private String  eventId;
    private String  occurredOn;
    private Integer timesTriedToPublish;

    public DomainEvent(String aggregateId) {
        this.aggregateId         = aggregateId;
        this.eventId             = UUID.randomUUID().toString();
        this.occurredOn          = Utils.now();
        this.timesTriedToPublish = 0;
    }

    public DomainEvent(String aggregateId, String eventId, String occurredOn, Integer timesTriedToPublish) {
        this.aggregateId         = aggregateId;
        this.eventId             = eventId;
        this.occurredOn          = occurredOn;
        this.timesTriedToPublish = timesTriedToPublish;
    }

    protected DomainEvent() {
    }

    public abstract String getEventName();

    public abstract Map<String, Serializable> toPrimitives();

    public Map<String, Serializable> toPrimitivesAll() {
        ObjectMapper mapper   = new ObjectMapper();
        MapType      javaType = mapper.getTypeFactory().constructMapType(HashMap.class, String.class, Serializable.class);
        return new ObjectMapper().convertValue(this, javaType);
    }

    public abstract DomainEvent fromPrimitives(
            String aggregateId,
            String eventId,
            String occurredOn,
            Integer timesTryingToPublish,
            Map<String, Serializable> body
    );

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getOccurredOn() {
        return occurredOn;
    }

    public void failedToPublish() {
        timesTriedToPublish++;
    }

    public Integer getTimesWasTriedToPublish() {
        return timesTriedToPublish;
    }
}

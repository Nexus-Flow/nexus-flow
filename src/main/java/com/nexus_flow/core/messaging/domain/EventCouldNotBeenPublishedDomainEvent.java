package com.nexus_flow.core.messaging.domain;


import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

public final class EventCouldNotBeenPublishedDomainEvent extends DomainEvent {

    private final String notConsumedEventName;
    private final String exchange;
    private final String errorMessage;
    private final String boundedContextName;


    public EventCouldNotBeenPublishedDomainEvent() {
        super(null);
        this.notConsumedEventName = null;
        this.exchange             = null;
        this.errorMessage         = null;
        this.boundedContextName   = null;
    }

    public EventCouldNotBeenPublishedDomainEvent(String aggregateId,
                                                 String notConsumedEventName,
                                                 String exchange,
                                                 String errorMessage,
                                                 String boundedContextName) {
        super(aggregateId);
        this.notConsumedEventName = notConsumedEventName;
        this.exchange             = exchange;
        this.errorMessage         = errorMessage;
        this.boundedContextName   = boundedContextName;
    }

    public EventCouldNotBeenPublishedDomainEvent(String aggregateId,
                                                 String eventId,
                                                 String occurredOn,
                                                 Integer timesTryingToPublish,

                                                 String notConsumedEventName,
                                                 String exchange,
                                                 String errorMessage,
                                                 String boundedContextName) {
        super(aggregateId, eventId, occurredOn, timesTryingToPublish);
        this.notConsumedEventName = notConsumedEventName;
        this.exchange             = exchange;
        this.errorMessage         = errorMessage;
        this.boundedContextName   = boundedContextName;
    }

    @Override
    public String getEventName() {
        return "nexus_flow.shared_bounded.1.event.event.not_published";
    }

    @Override
    public Map<String, Serializable> toPrimitives() {
        return toPrimitivesAll();
    }

    @Override
    public EventCouldNotBeenPublishedDomainEvent fromPrimitives(String aggregateId,
                                                                String eventId,
                                                                String occurredOn,
                                                                Integer timesTryingToPublish,
                                                                Map<String, Serializable> body) {

        return new EventCouldNotBeenPublishedDomainEvent(
                aggregateId,
                eventId,
                occurredOn,
                timesTryingToPublish,
                (String) body.get("notConsumedEventName"),
                (String) body.get("queue"),
                (String) body.get("errorMessage"),
                (String) body.get("boundedContextName")
        );

    }

    public String getNotConsumedEventName() {
        return notConsumedEventName;
    }

    public String getExchange() {
        return exchange;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getBoundedContextName() {
        return boundedContextName;
    }

    @Override
    public int hashCode() {
        return Objects.hash(notConsumedEventName, exchange, errorMessage, boundedContextName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EventCouldNotBeenPublishedDomainEvent that = (EventCouldNotBeenPublishedDomainEvent) o;
        return Objects.equals(notConsumedEventName, that.notConsumedEventName) &&
                Objects.equals(exchange, that.exchange) &&
                Objects.equals(errorMessage, that.errorMessage) &&
                Objects.equals(boundedContextName, that.boundedContextName);
    }
}

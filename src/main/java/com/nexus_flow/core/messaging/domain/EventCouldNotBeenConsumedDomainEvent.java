package com.nexus_flow.core.messaging.domain;


import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

public final class EventCouldNotBeenConsumedDomainEvent extends DomainEvent {

    private final String notConsumedEventName;
    private final String queue;
    private final String errorMessage;
    private final String boundedContextName;


    public EventCouldNotBeenConsumedDomainEvent() {
        super(null);
        this.notConsumedEventName = null;
        this.queue                = null;
        this.errorMessage         = null;
        this.boundedContextName   = null;
    }

    public EventCouldNotBeenConsumedDomainEvent(String aggregateId,
                                                String notConsumedEventName,
                                                String queue,
                                                String errorMessage,
                                                String boundedContextName) {
        super(aggregateId);
        this.notConsumedEventName = notConsumedEventName;
        this.queue                = queue;
        this.errorMessage         = errorMessage;
        this.boundedContextName   = boundedContextName;
    }

    public EventCouldNotBeenConsumedDomainEvent(String aggregateId,
                                                String eventId,
                                                String occurredOn,
                                                Integer timesTryingToPublish,

                                                String notConsumedEventName,
                                                String queue,
                                                String errorMessage,
                                                String boundedContextName) {
        super(aggregateId, eventId, occurredOn, timesTryingToPublish);
        this.notConsumedEventName = notConsumedEventName;
        this.queue                = queue;
        this.errorMessage         = errorMessage;
        this.boundedContextName   = boundedContextName;
    }

    @Override
    public String getEventName() {
        return "nexus_flow.shared_bounded.1.event.event.not_consumed";
    }

    @Override
    public Map<String, Serializable> toPrimitives() {
        return toPrimitivesAll();
    }

    @Override
    public EventCouldNotBeenConsumedDomainEvent fromPrimitives(String aggregateId,
                                                               String eventId,
                                                               String occurredOn,
                                                               Integer timesTryingToPublish,
                                                               Map<String, Serializable> body) {

        return new EventCouldNotBeenConsumedDomainEvent(
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

    public String getQueue() {
        return queue;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getBoundedContextName() {
        return boundedContextName;
    }

    @Override
    public int hashCode() {
        return Objects.hash(notConsumedEventName, queue, errorMessage, boundedContextName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EventCouldNotBeenConsumedDomainEvent that = (EventCouldNotBeenConsumedDomainEvent) o;
        return Objects.equals(notConsumedEventName, that.notConsumedEventName) &&
                Objects.equals(queue, that.queue) &&
                Objects.equals(errorMessage, that.errorMessage) &&
                Objects.equals(boundedContextName, that.boundedContextName);
    }
}

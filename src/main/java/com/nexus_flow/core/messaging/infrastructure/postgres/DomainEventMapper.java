package com.nexus_flow.core.messaging.infrastructure.postgres;


import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import com.nexus_flow.core.ddd.exceptions.RetrievingEventFromDatabaseError;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.messaging.infrastructure.DomainEventsCollector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@NexusFlowService
public class DomainEventMapper {

    private final DomainEventsCollector domainEventsInformation;

    protected DomainEventMapper(DomainEventsCollector domainEventsInformation) {
        this.domainEventsInformation = domainEventsInformation;
    }

    List<DomainEvent> toDomain(List<DomainEventEntity> domainEventEntities) {

        if (domainEventEntities == null) {
            return new ArrayList<>();
        }

        List<DomainEvent> list = new ArrayList<>(domainEventEntities.size());

        for (DomainEventEntity domainEventEntity : domainEventEntities) {
            list.add(toDomain(domainEventEntity));
        }

        return list;
    }


    public DomainEvent toDomain(DomainEventEntity eventEntity) {

        if (eventEntity == null) return null;

        Object domainEvent;

        try {
            Class<? extends DomainEvent> domainEventClass = domainEventsInformation.forName(eventEntity.getName());

            DomainEvent nullInstance = domainEventClass.getConstructor().newInstance();

            Method fromPrimitivesMethod = domainEventClass.getMethod(
                    "fromPrimitives",
                    String.class,
                    String.class,
                    String.class,
                    Integer.class,
                    Map.class
            );

            domainEvent = fromPrimitivesMethod.invoke(
                    nullInstance,
                    eventEntity.getAggregateId(),
                    eventEntity.getEventId(),
                    Utils.dateToString(eventEntity.getOccurredOn()),
                    eventEntity.getAttemptsToPublish(),
                    Utils.jsonDecode(eventEntity.getBody())
            );

        } catch (Exception e) {
            throw new RetrievingEventFromDatabaseError(e);
        }

        return (DomainEvent) domainEvent;

    }

    public DomainEventEntity toEntity(DomainEvent event) {
        if (event == null) {
            return null;
        }

        DomainEventEntity domainEventEntity = new DomainEventEntity();

        domainEventEntity.setEventId(event.getEventId());
        domainEventEntity.setAggregateId(event.getAggregateId());
        domainEventEntity.setName(event.getEventName());
        domainEventEntity.setBody(Utils.jsonEncode(event.toPrimitives()));
        domainEventEntity.setOccurredOn(Utils.stringToTimestamp(event.getOccurredOn()));
        domainEventEntity.setAttemptsToPublish(event.getTimesWasTriedToPublish());

        return domainEventEntity;
    }

    List<DomainEventEntity> toEntity(List<DomainEvent> domainEvents) {
        if (domainEvents == null) {
            return new ArrayList<>();
        }

        List<DomainEventEntity> list = new ArrayList<>(domainEvents.size());
        for (DomainEvent domainEvent : domainEvents) {
            list.add(toEntity(domainEvent));
        }

        return list;
    }


}

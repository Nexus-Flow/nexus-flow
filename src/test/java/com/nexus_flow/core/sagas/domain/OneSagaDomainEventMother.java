package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.domain.DateTimeUTCMother;
import com.nexus_flow.core.domain.UuidMother;
import com.nexus_flow.core.messaging.domain.DomainEvent;

public class OneSagaDomainEventMother {

    public static OneSagaDomainEvent create(String aggregateId,
                                            String eventId,
                                            String occurredOn,
                                            Integer timesTryingToPublish,
                                            String sagaTriggerId,
                                            String sagaTriggeredOn) {
        return new OneSagaDomainEvent(aggregateId, eventId, occurredOn, timesTryingToPublish, sagaTriggerId, sagaTriggeredOn);
    }

    public static OneSagaDomainEvent from(SagaCommand sagaCommand) {
        return create(UuidMother.random(),
                UuidMother.random(),
                Utils.dateToString(DateTimeUTCMother.randomPastForEvents()),
                0,
                sagaCommand.getCommandId(),
                sagaCommand.getSagaTriggeredOn());
    }

    public static OneSagaDomainEvent from(DomainEvent domainEvent) {
        return create(UuidMother.random(),
                domainEvent.getEventId(),
                Utils.dateToString(DateTimeUTCMother.randomPastForEvents()),
                0,
                domainEvent.getEventId(),
                domainEvent.getOccurredOn());
    }

    public static OneSagaDomainEvent randomWithSagaInfo() {
        return create(UuidMother.random(),
                UuidMother.random(),
                Utils.dateToString(DateTimeUTCMother.randomPastForEvents()),
                0,
                UuidMother.random(),
                Utils.dateToString(DateTimeUTCMother.randomPastForEvents()));
    }

    public static OneSagaDomainEvent randomWithoutSagaInfo() {
        return create(UuidMother.random(),
                UuidMother.random(),
                Utils.dateToString(DateTimeUTCMother.randomPastForEvents()),
                0,
                null,
                null);
    }
}

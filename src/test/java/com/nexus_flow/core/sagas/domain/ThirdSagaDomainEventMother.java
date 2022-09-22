package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.domain.DateTimeUTCMother;
import com.nexus_flow.core.domain.UuidMother;
import com.nexus_flow.core.messaging.domain.DomainEvent;

public class ThirdSagaDomainEventMother {

    public static ThirdSagaDomainEvent create(String aggregateId,
                                              String eventId,
                                              String occurredOn,
                                              Integer timesTryingToPublish,
                                              String sagaTriggerId,
                                              String sagaTriggeredOn) {
        return new ThirdSagaDomainEvent(aggregateId, eventId, occurredOn, timesTryingToPublish, sagaTriggerId, sagaTriggeredOn);
    }

    public static ThirdSagaDomainEvent randomWithTriggerInfo(SagaCommand sagaCommand) {
        return create(UuidMother.random(),
                UuidMother.random(),
                Utils.dateToString(DateTimeUTCMother.randomPastForEvents()),
                0,
                sagaCommand.getCommandId(),
                sagaCommand.getSagaTriggeredOn());
    }

    public static ThirdSagaDomainEvent randomWithTriggerInfo(DomainEvent domainEvent) {
        return create(UuidMother.random(),
                domainEvent.getEventId(),
                Utils.dateToString(DateTimeUTCMother.randomPastForEvents()),
                0,
                domainEvent.getEventId(),
                domainEvent.getOccurredOn());
    }

    public static ThirdSagaDomainEvent randomWithSagaInfo() {
        return create(UuidMother.random(),
                UuidMother.random(),
                Utils.dateToString(DateTimeUTCMother.randomPastForEvents()),
                0,
                UuidMother.random(),
                Utils.dateToString(DateTimeUTCMother.randomPastForEvents()));
    }

    public static ThirdSagaDomainEvent randomWithoutSagaInfo() {
        return create(UuidMother.random(),
                UuidMother.random(),
                Utils.dateToString(DateTimeUTCMother.randomPastForEvents()),
                0,
                null,
                null);
    }
}

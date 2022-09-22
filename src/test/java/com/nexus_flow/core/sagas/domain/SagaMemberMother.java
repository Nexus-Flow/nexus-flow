package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.sagas.domain.value_objects.SagaType;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.*;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.OtherInvolvedEventsClasses;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.SagaName;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.TimeOut;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.TriggeredBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SagaMemberMother {

    public static SagaMember create(SagaMemberId sagaMemberId,
                                    SagaMemberClass sagaMemberClass,
                                    SagaTriggerId sagaTriggerId,
                                    SagaName sagaName,
                                    SagaTriggeredOn triggeredOn,
                                    SagaMemberPayload sagaMemberPayload
    ) {
        return new SagaMember(
                sagaMemberId,
                sagaMemberClass,
                sagaTriggerId,
                sagaName,
                triggeredOn,
                sagaMemberPayload
        );
    }

    public static SagaMember fromCommandTrigger(SagaCommand commandTrigger, SagaType sagaType) {
        return create(
                new SagaMemberId(commandTrigger.getCommandId()),
                new SagaMemberClass(commandTrigger.getClass()),
                new SagaTriggerId(commandTrigger.getCommandId()),
                new SagaName(sagaType.getName().getValue()),
                new SagaTriggeredOn(Utils.stringToZonedDateTime(commandTrigger.getSagaTriggeredOn())),
                new CommandSagaMember(commandTrigger)
        );
    }

    public static SagaMember fromEventTrigger(DomainEvent triggerEvent, SagaType sagaType) {
        return create(
                new SagaMemberId(triggerEvent.getEventId()),
                new SagaMemberClass(triggerEvent.getClass()),
                new SagaTriggerId(triggerEvent.getEventId()),
                new SagaName(sagaType.getName().getValue()),
                new SagaTriggeredOn(Utils.stringToZonedDateTime(triggerEvent.getOccurredOn())),
                new EventTriggerSagaMember(triggerEvent)
        );
    }

    public static SagaMember fromNotTriggerEvent(SagaDomainEvent notTriggerEvent, SagaType sagaType) {
        return create(
                new SagaMemberId(notTriggerEvent.getEventId()),
                new SagaMemberClass(notTriggerEvent.getClass()),
                new SagaTriggerId(notTriggerEvent.getSagaTriggerId()),
                new SagaName(sagaType.getName().getValue()),
                new SagaTriggeredOn(Utils.stringToZonedDateTime(notTriggerEvent.getSagaTriggeredOn())),
                new EventNotTriggerSagaMember(notTriggerEvent)
        );
    }

    public static List<SagaMember> fromTriggerAndNotTrigger(SagaCommand sagaCommand,
                                                            SagaType sagaType,
                                                            SagaDomainEvent sagaDomainEvent) {
        List<SagaMember> sagaMembers = new ArrayList<>();
        sagaMembers.add(fromCommandTrigger(sagaCommand, sagaType));
        sagaMembers.add(fromNotTriggerEvent(sagaDomainEvent, sagaType));
        return sagaMembers;
    }

    public static List<SagaMember> fromTriggerAndNotTrigger(DomainEvent triggerEvent,
                                                            SagaType sagaType,
                                                            SagaDomainEvent notTriggerEvent) {
        List<SagaMember> sagaMembers = new ArrayList<>();
        sagaMembers.add(fromEventTrigger(triggerEvent, sagaType));
        sagaMembers.add(fromNotTriggerEvent(notTriggerEvent, sagaType));
        return sagaMembers;
    }

    public static List<SagaMember> randomTriggeredByCommand() {
        SagaType oneSagaCommandSagaType = new SagaType(
                new SagaName("Test saga with a command trigger."),
                new TriggeredBy(OneSagaCommand.class),
                new OtherInvolvedEventsClasses(Collections.singletonList(OneSagaDomainEvent.class)),
                new TimeOut(Duration.ofSeconds(5))
        );
        OneSagaCommand     sagaCommand        = OneSagaCommandMother.random();
        OneSagaDomainEvent oneSagaDomainEvent = OneSagaDomainEventMother.from(sagaCommand);
        return fromTriggerAndNotTrigger(sagaCommand, oneSagaCommandSagaType, oneSagaDomainEvent);
    }

}

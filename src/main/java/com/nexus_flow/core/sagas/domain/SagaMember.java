package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.sagas.domain.value_objects.SagaType;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.*;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.SagaName;

import java.util.Objects;

public class SagaMember {

    private SagaMemberId sagaMemberId;
    private SagaMemberClass sagaMemberClass;
    private SagaTriggerId sagaTriggerId;
    private SagaName sagaName;
    private SagaTriggeredOn sagaTriggeredOn;
    private SagaMemberPayload member;

    private SagaMember() {
    }

    public SagaMember(SagaMemberId sagaMemberId,
                      SagaMemberClass sagaMemberClass,
                      SagaTriggerId sagaTriggerId,
                      SagaName sagaName,
                      SagaTriggeredOn sagaTriggeredOn,
                      SagaMemberPayload member) {
        this.sagaMemberId    = sagaMemberId;
        this.sagaMemberClass = sagaMemberClass;
        this.sagaTriggerId   = sagaTriggerId;
        this.sagaName        = sagaName;
        this.member          = member;
        this.sagaTriggeredOn = sagaTriggeredOn;
    }

    public static SagaMember createFromNotTrigger(SagaDomainEvent notTriggerEvent, SagaType sagaType) {
        return new SagaMember(
                new SagaMemberId(notTriggerEvent.getEventId()),
                new SagaMemberClass(notTriggerEvent.getClass()),
                new SagaTriggerId(notTriggerEvent.getSagaTriggerId()),
                new SagaName(sagaType.getName().getValue()),
                new SagaTriggeredOn(Utils.stringToZonedDateTime(notTriggerEvent.getSagaTriggeredOn())),
                new EventNotTriggerSagaMember(notTriggerEvent)
        );

    }

    public static SagaMember createFromTrigger(DomainEvent triggerEvent, SagaType sagaType) {
        return new SagaMember(
                new SagaMemberId(triggerEvent.getEventId()),
                new SagaMemberClass(triggerEvent.getClass()),
                new SagaTriggerId(triggerEvent.getEventId()),
                new SagaName(sagaType.getName().getValue()),
                new SagaTriggeredOn(Utils.stringToZonedDateTime(triggerEvent.getOccurredOn())),
                new EventTriggerSagaMember(triggerEvent)
        );
    }

    public static SagaMember createFromTrigger(SagaCommand commandTrigger, SagaType sagaType) {
        return new SagaMember(
                new SagaMemberId(commandTrigger.getCommandId()),
                new SagaMemberClass(commandTrigger.getClass()),
                new SagaTriggerId(commandTrigger.getCommandId()),
                new SagaName(sagaType.getName().getValue()),
                new SagaTriggeredOn(Utils.stringToZonedDateTime(commandTrigger.getSagaTriggeredOn())),
                new CommandSagaMember(commandTrigger)
        );
    }

    public SagaMemberId getSagaMemberId() {
        return sagaMemberId;
    }

    public SagaMemberClass getSagaMemberClass() {
        return sagaMemberClass;
    }

    public SagaTriggerId getSagaTriggerId() {
        return sagaTriggerId;
    }

    public SagaName getSagaName() {
        return sagaName;
    }

    public SagaMemberPayload getMember() {
        return member;
    }

    public SagaTriggeredOn getSagaTriggeredOn() {
        return sagaTriggeredOn;
    }

    public String logInfo(SagaType sagaType){
        return  "Saga '" + sagaType.getName() + "' Trigger id: <" + sagaTriggerId.getValue() + ">. " +
                "Triggered on: " + Utils.dateToString(sagaTriggeredOn.getValue()) + " |--> ";
    }
    @Override
    public int hashCode() {
        return Objects.hash(sagaMemberId, sagaMemberClass, sagaTriggerId, sagaName, member, sagaTriggeredOn);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaMember that = (SagaMember) o;
        return Objects.equals(sagaMemberId, that.sagaMemberId) &&
                Objects.equals(sagaMemberClass, that.sagaMemberClass) &&
                Objects.equals(sagaTriggerId, that.sagaTriggerId) &&
                Objects.equals(sagaName, that.sagaName) &&
                Objects.equals(member, that.member) &&
                Objects.equals(sagaTriggeredOn, that.sagaTriggeredOn);
    }
}

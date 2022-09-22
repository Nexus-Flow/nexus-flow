package com.nexus_flow.core.sagas.domain.value_objects;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.sagas.domain.SagaCommand;
import com.nexus_flow.core.sagas.domain.SagaDomainEvent;
import com.nexus_flow.core.sagas.domain.exceptions.NotADomainEventForThisSaga;
import com.nexus_flow.core.sagas.domain.exceptions.NotATriggerForThisSaga;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaMemberClass;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaTriggerId;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaTriggeredOn;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class SagaType {

    private final SagaName name;
    private final TriggeredBy triggeredBy;
    private final OtherInvolvedEventsClasses otherInvolvedEventsClasses;
    private final TimeOut timeOut;
    private       List<Class<?>>             allClasses;

    public SagaType(SagaName name,
                    TriggeredBy triggeredBy,
                    OtherInvolvedEventsClasses otherInvolvedEventsClasses,
                    TimeOut timeOut) {
        this.name                       = name;
        this.triggeredBy                = triggeredBy;
        this.otherInvolvedEventsClasses = otherInvolvedEventsClasses;
        this.timeOut                    = timeOut;
        this.allClasses                 = allClasses();
    }

    private List<Class<?>> allClasses() {
        if (allClasses == null) {
            List<Class<?>> classes = new ArrayList<>(otherInvolvedEventsClasses.getEvents());
            classes.add(triggeredBy.getValue());
            this.allClasses = classes;
        }
        return this.allClasses;
    }

    public boolean checkHasSagaFinished(List<SagaMemberClass> sagaMembers) {
        return sagaMembersToClassValues(sagaMembers).containsAll(allClasses());
    }

    public List<SagaMemberClass> checkMissingMembers(List<SagaMemberClass> sagaMembers) {
        List<? extends Class<?>> sagaMembersValues = sagaMembersToClassValues(sagaMembers);
        return allClasses().stream()
                .filter(aClass -> !sagaMembersValues.contains(aClass))
                .map(SagaMemberClass::new)
                .collect(Collectors.toList());
    }

    private List<? extends Class<?>> sagaMembersToClassValues(List<SagaMemberClass> sagaMembers) {
        return sagaMembers.stream()
                .map(SagaMemberClass::getValue)
                .collect(Collectors.toList());
    }

    public SagaTriggerId retrieveTriggerId(SagaCommand incomingCommand) {
        checkNotNull(incomingCommand);
        checkIsTriggerForThisSaga(incomingCommand);
        return new SagaTriggerId(incomingCommand.getCommandId());
    }

    public SagaTriggerId retrieveTriggerId(DomainEvent incomingEvent) {
        checkNotNull(incomingEvent);
        SagaTriggerId triggerId;
        try {
            checkIsTriggerForThisSaga(incomingEvent);
            triggerId = new SagaTriggerId(incomingEvent.getEventId());
        } catch (NotATriggerForThisSaga e) {
            if (incomingEvent instanceof SagaDomainEvent) {
                checkIsAValidNotTriggerEventForThisSaga((SagaDomainEvent) incomingEvent);
                triggerId = new SagaTriggerId(((SagaDomainEvent) incomingEvent).getSagaTriggerId());
            } else {
                throw new NotADomainEventForThisSaga(incomingEvent);
            }
        }
        return triggerId;
    }

    public SagaTriggeredOn retrieveTriggeredOn(SagaCommand incomingCommand) {
        checkNotNull(incomingCommand);
        checkIsTriggerForThisSaga(incomingCommand);
        return new SagaTriggeredOn(incomingCommand.getSagaTriggeredOn());
    }

    public SagaTriggeredOn retrieveTriggeredOn(DomainEvent incomingEvent) {
        checkNotNull(incomingEvent);
        SagaTriggeredOn triggeredOn;
        if (incomingEvent instanceof SagaDomainEvent) {
            checkIsAValidNotTriggerEventForThisSaga((SagaDomainEvent) incomingEvent);
            triggeredOn = new SagaTriggeredOn(((SagaDomainEvent) incomingEvent).getSagaTriggeredOn());
        } else {
            checkIsTriggerForThisSaga(incomingEvent);
            triggeredOn = new SagaTriggeredOn(incomingEvent.getOccurredOn());
        }
        return triggeredOn;
    }

    public <R> void checkIsTriggerForThisSaga(R supposedTrigger) {
        checkNotNull(supposedTrigger);
        if (!supposedTrigger.getClass().equals(triggeredBy.getValue())) {
            throw new NotATriggerForThisSaga(this, supposedTrigger.getClass());
        }
    }

    public void checkIsAValidNotTriggerEventForThisSaga(SagaDomainEvent domainEvent) {
        checkNotNull(domainEvent);
        if (!otherInvolvedEventsClasses.checkIsInvolvedEvent(domainEvent)) {
            throw new NotADomainEventForThisSaga(domainEvent);
        }
    }

    public TriggerType triggerType() {
        return this.triggeredBy.getTriggerType();
    }

    public boolean checkIfTriggerOfTypeEvent() {
        return this.triggerType().equals(TriggerType.EVENT);
    }

    public boolean checkIfTriggerOfTypeCommand() {
        return this.triggerType().equals(TriggerType.COMMAND);
    }

    private void checkNotNull(Object incomingEvent) {
        if (incomingEvent == null) {
            throw new WrongFormat(this.getClass());
        }
    }

    public SagaName getName() {
        return name;
    }

    public TriggeredBy getTriggeredBy() {
        return triggeredBy;
    }

    public OtherInvolvedEventsClasses getOtherInvolvedEventsClasses() {
        return otherInvolvedEventsClasses;
    }

    public TimeOut getTimeOut() {
        return timeOut;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, triggeredBy, otherInvolvedEventsClasses, timeOut);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaType sagaType = (SagaType) o;
        return Objects.equals(name, sagaType.name) &&
                Objects.equals(triggeredBy, sagaType.triggeredBy) &&
                Objects.equals(otherInvolvedEventsClasses, sagaType.otherInvolvedEventsClasses) &&
                Objects.equals(timeOut, sagaType.timeOut);
    }
}

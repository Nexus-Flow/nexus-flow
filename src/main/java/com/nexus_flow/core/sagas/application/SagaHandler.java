package com.nexus_flow.core.sagas.application;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.sagas.domain.SagaCommand;
import com.nexus_flow.core.sagas.domain.SagaDomainEvent;
import com.nexus_flow.core.sagas.domain.SagaMember;
import com.nexus_flow.core.sagas.domain.SagaRepository;
import com.nexus_flow.core.sagas.domain.exceptions.NotADomainEventForThisSaga;
import com.nexus_flow.core.sagas.domain.exceptions.NotATriggerForThisSaga;
import com.nexus_flow.core.sagas.domain.exceptions.SagaTimeOut;
import com.nexus_flow.core.sagas.domain.value_objects.SagaType;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaMemberClass;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaTriggerId;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaTriggeredOn;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.TriggerType;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public abstract class SagaHandler {

    protected final SagaType sagaType;
    protected final SagaRepository repository;
    private         String         logSagaInfo = "";

    protected SagaHandler(SagaType sagaType, SagaRepository repository) {
        this.sagaType   = sagaType;
        this.repository = repository;

    }

    public boolean processAndCheckIfFinishedWith(SagaDomainEvent incomingSagaEvent) {
        if (hasSagaInfo(incomingSagaEvent)) {
            // If it has saga info inside, most probably is a NON trigger saga domain event for this saga type
            if (!needsToBeProcessedAsNonTrigger(incomingSagaEvent)) return false;
            processNotTriggerEvent(incomingSagaEvent);
        } else {
            // If it has NOT saga info inside, most probably is the TRIGGER saga domain event for this saga type
            if (!needsToBeProcessedAsTrigger(incomingSagaEvent)) return false;
            processTrigger(incomingSagaEvent);
        }
        SagaTriggerId triggerId = obtainTriggerId(incomingSagaEvent);
        return checkIsSagaFinished(triggerId);
    }

    /**
     * Checks a SagaDomainEvent <b>WITH SAGA INFO</b> inside. Most probably it is a non trigger event for this saga.
     * If it is not, checks if the class of this event acts as a trigger in this saga, but,
     * as long as the instance has saga info, this instance doesn't belong to this saga. In that case, we don't need
     * to process it, but we cannot throw an exception, because we have to keep listening to these events.
     * If the event is neither a trigger nor an involved event, it should not be listened to: an exception is thrown.
     *
     * @param incomingSagaEvent SagaDomainEvent <b>WITH SAGA INFO</b>
     * @return true if is a valid NON trigger event that needs to be processed <br>
     * false if it is involved as valid trigger, but this instance is not acting as it in this saga
     * @throws NotADomainEventForThisSaga if the event is neither a trigger, nor an involved event
     */
    private boolean needsToBeProcessedAsNonTrigger(SagaDomainEvent incomingSagaEvent) {
        try {
            sagaType.checkIsAValidNotTriggerEventForThisSaga(incomingSagaEvent);
            return true;
        } catch (NotADomainEventForThisSaga e) {
            try {
                sagaType.checkIsTriggerForThisSaga(incomingSagaEvent);
                return false;
            } catch (NotATriggerForThisSaga exception) {
                throw new NotADomainEventForThisSaga(incomingSagaEvent);
            }
        }
    }

    /**
     * Checks a SagaDomainEvent <b>WITHOUT SAGA INFO</b> inside. Most probably it is the trigger event for this saga.
     * If it is not, checks if this event class is involved as a non trigger event in this saga, but,
     * as long as the instance has NOT saga info, this instance doesn't belong to this saga. In that case, we don't need
     * to process it, but we cannot throw an exception, because we have to keep listening to these events.
     * If the event is neither a trigger nor an involved event, it should not be listened to: an exception is thrown.
     *
     * @param incomingSagaEvent SagaDomainEvent <b>WITHOUT</b> saga info inside
     * @return true if is a valid trigger event that needs to be processed <br>
     * false if it is involved as a valid non trigger event, but this instance is not acting as it in this saga
     * @throws NotADomainEventForThisSaga if the event is neither a trigger, nor an involved event
     */
    private boolean needsToBeProcessedAsTrigger(SagaDomainEvent incomingSagaEvent) {
        try {
            sagaType.checkIsTriggerForThisSaga(incomingSagaEvent);
            return true;
        } catch (NotATriggerForThisSaga e) {
            sagaType.checkIsAValidNotTriggerEventForThisSaga(incomingSagaEvent);
            return false;
        }
    }

    public boolean processAndCheckIfFinishedWith(DomainEvent incomingEvent) {
        sagaType.checkIsTriggerForThisSaga(incomingEvent);
        processTrigger(incomingEvent);
        SagaTriggerId triggerId = obtainTriggerId(incomingEvent);
        return checkIsSagaFinished(triggerId);
    }

    public boolean processAndCheckIfFinishedWith(SagaCommand sagaCommand) {
        sagaType.checkIsTriggerForThisSaga(sagaCommand);
        processTrigger(sagaCommand);
        SagaTriggerId triggerId = obtainTriggerId(sagaCommand);
        return checkIsSagaFinished(triggerId);
    }

    public void processAndBlockUntilFinished(DomainEvent incomingEvent) {
        if (incomingEvent instanceof SagaDomainEvent) {
            throw new RuntimeException("Blocking request is not yet available for SagaDomainEvents");
        }
        SagaTriggerId   triggerId   = obtainTriggerId(incomingEvent);
        SagaTriggeredOn triggeredOn = obtainTriggeredOn(incomingEvent);
        if (processAndCheckIfFinishedWith(incomingEvent)) {
            deleteSaga(triggerId);
            return;
        }
        block(triggerId, triggeredOn);
    }

    public void processAndBlockUntilFinished(SagaCommand incomingCommand) {
        SagaTriggerId   triggerId   = obtainTriggerId(incomingCommand);
        SagaTriggeredOn triggeredOn = obtainTriggeredOn(incomingCommand);
        if (processAndCheckIfFinishedWith(incomingCommand)) {
            deleteSaga(triggerId);
            return;
        }
        block(triggerId, triggeredOn);
    }

    @SneakyThrows
    private void block(SagaTriggerId triggerId, SagaTriggeredOn triggeredOn) {
        boolean isSagaFinished;
        boolean isTimedOut;
        do {
            isSagaFinished = checkIsSagaFinished(triggerId);
            isTimedOut     = checkIsTimedOut(triggeredOn);
//            Thread.sleep(5);
        }
        while (!isSagaFinished && !isTimedOut);

        if (isTimedOut) {
            // It has no rollback for the moment
            log("HAS TIMED OUT ");
            List<SagaMemberClass> sagaMemberClasses = checkMissingSagaMembers(triggerId);
            deleteSaga(triggerId);
            throw new SagaTimeOut(sagaType.getName(), triggerId, triggeredOn, sagaMemberClasses);
        }

        log("FINISHED ");
        deleteSaga(triggerId);
    }

    private void processTrigger(SagaCommand sagaCommand) {
        SagaMember triggerCommandMember = SagaMember.createFromTrigger(sagaCommand, sagaType);
        repository.saveSagaMember(triggerCommandMember);
        log(triggerCommandMember, "PROCESSED TRIGGER ");
    }

    private void processTrigger(DomainEvent triggerEvent) {
        SagaMember triggerEventMember = SagaMember.createFromTrigger(triggerEvent, sagaType);
        repository.saveSagaMember(triggerEventMember);
        log(triggerEventMember, "PROCESSED TRIGGER ");
    }

    private void processNotTriggerEvent(SagaDomainEvent notTriggerEvent) {
        if (!matchTriggerName(notTriggerEvent.getSagaTriggerId())) return;
        SagaMember notTriggerMember = SagaMember.createFromNotTrigger(notTriggerEvent, sagaType);
        repository.saveSagaMember(notTriggerMember);
        log(notTriggerMember, "PROCESSED ");
    }

    private boolean matchTriggerName(String triggerEvent) {
        if (this.sagaType.getTriggeredBy().getTriggerType().equals(TriggerType.COMMAND))
            return triggerEvent.startsWith(this.sagaType.getTriggeredBy().getValue().getSimpleName() + "#");
        else
            return true;
    }

    private boolean checkIsSagaFinished(SagaTriggerId sagaTriggerId) {
        List<SagaMemberClass> sagaMembers = obtainArrivedMembers(sagaTriggerId);
        return sagaType.checkHasSagaFinished(sagaMembers);
    }

    private boolean checkIsTimedOut(SagaTriggeredOn sagaTriggeredOn) {
        return Utils.nowZonedDateTime()
                .isAfter(sagaTriggeredOn.getValue().plus(sagaType.getTimeOut().getValue()));
    }

    private boolean hasSagaInfo(SagaDomainEvent incomingEvent) {
        String sagaTriggerId   = incomingEvent.getSagaTriggerId();
        String sagaTriggeredOn = incomingEvent.getSagaTriggeredOn();
        return sagaTriggerId != null && !sagaTriggerId.isBlank() &&
                sagaTriggeredOn != null && !sagaTriggeredOn.isBlank();
    }

    private List<SagaMemberClass> checkMissingSagaMembers(SagaTriggerId triggerId) {
        return sagaType.checkMissingMembers(obtainArrivedMembers(triggerId));
    }

    private List<SagaMemberClass> obtainArrivedMembers(SagaTriggerId sagaTriggerId) {
        return repository.searchArrivedMembers(sagaTriggerId);
    }

    private SagaTriggerId obtainTriggerId(SagaCommand incomingObject) {
        return sagaType.retrieveTriggerId(incomingObject);
    }

    private SagaTriggerId obtainTriggerId(DomainEvent incomingObject) {
        return sagaType.retrieveTriggerId(incomingObject);
    }

    private SagaTriggeredOn obtainTriggeredOn(SagaCommand incomingCommand) {
        return sagaType.retrieveTriggeredOn(incomingCommand);
    }

    private SagaTriggeredOn obtainTriggeredOn(DomainEvent incomingEvent) {
        return sagaType.retrieveTriggeredOn(incomingEvent);
    }

    protected void deleteSaga(SagaTriggerId id) {
        repository.deleteSaga(id);
        log("DELETED ");
    }

    private void log(SagaMember incomingMember, String action) {
        this.logSagaInfo = incomingMember.logInfo(sagaType);
        log.info(this.logSagaInfo + action + incomingMember.getSagaMemberClass().getValue().getSimpleName());
    }

    private void log(String action) {
        log.info(this.logSagaInfo + action);
    }

    public SagaType getSagaType() {
        return sagaType;
    }
}



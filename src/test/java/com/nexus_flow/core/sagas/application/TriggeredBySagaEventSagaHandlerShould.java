package com.nexus_flow.core.sagas.application;

import com.nexus_flow.core.sagas.SagaHandlerUnitTestCase;
import com.nexus_flow.core.sagas.application.test_saga_handlers.TriggeredBySagaEventSagaHandler;
import com.nexus_flow.core.sagas.domain.*;
import com.nexus_flow.core.sagas.domain.exceptions.NotADomainEventForThisSaga;
import com.nexus_flow.core.sagas.domain.exceptions.NotATriggerForThisSaga;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaMemberClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class TriggeredBySagaEventSagaHandlerShould extends SagaHandlerUnitTestCase {

    @Mock
    private TriggeredBySagaEventSagaHandler sagaEventTriggeredSagaHandler;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        sagaEventTriggeredSagaHandler = new TriggeredBySagaEventSagaHandler(repository);
    }


    @Test
    void saga_event_triggered___first_trigger_then_event___finished_saga_ok() {

        OneSagaDomainEvent sagaEventAsTrigger = OneSagaDomainEventMother.randomWithoutSagaInfo();
        SagaMember sagaEventAsTriggerSagaMember = SagaMemberMother
                .fromEventTrigger(sagaEventAsTrigger, sagaEventTriggeredSagaHandler.getSagaType());

        AnotherSagaDomainEvent anotherSagaEventNotTrigger = AnotherSagaDomainEventMother
                .randomWithTriggerInfo(sagaEventAsTrigger);
        SagaMember anotherSagaEventNotTriggerSagaMember = SagaMemberMother
                .fromNotTriggerEvent(anotherSagaEventNotTrigger, sagaEventTriggeredSagaHandler.getSagaType());

        List<SagaMemberClass> processedSagaMembers = new ArrayList<>();

        // First comes saga event as trigger
        processedSagaMembers.add(SagaMemberClassMother.fromMember(sagaEventAsTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertFalse(sagaEventTriggeredSagaHandler.processAndCheckIfFinishedWith(sagaEventAsTrigger));
        shouldHaveSaved(sagaEventAsTriggerSagaMember);


        // Then comes the not trigger saga event
        processedSagaMembers.add(SagaMemberClassMother.fromMember(anotherSagaEventNotTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertTrue(sagaEventTriggeredSagaHandler.processAndCheckIfFinishedWith(anotherSagaEventNotTrigger));
        shouldHaveSaved(anotherSagaEventNotTriggerSagaMember);
    }

    @Test
    void saga_event_triggered___first_not_trigger_then_trigger___finished_saga_ok() {

        OneSagaDomainEvent sagaEventAsTrigger = OneSagaDomainEventMother.randomWithoutSagaInfo();
        SagaMember sagaEventAsTriggerSagaMember = SagaMemberMother
                .fromEventTrigger(sagaEventAsTrigger, sagaEventTriggeredSagaHandler.getSagaType());

        AnotherSagaDomainEvent anotherSagaEventNotTrigger = AnotherSagaDomainEventMother
                .randomWithTriggerInfo(sagaEventAsTrigger);
        SagaMember anotherSagaEventNotTriggerSagaMember = SagaMemberMother
                .fromNotTriggerEvent(anotherSagaEventNotTrigger, sagaEventTriggeredSagaHandler.getSagaType());

        List<SagaMemberClass> processedSagaMembers = new ArrayList<>();

        // First comes the not trigger saga event
        processedSagaMembers.add(SagaMemberClassMother.fromMember(anotherSagaEventNotTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertFalse(sagaEventTriggeredSagaHandler.processAndCheckIfFinishedWith(anotherSagaEventNotTrigger));
        shouldHaveSaved(anotherSagaEventNotTriggerSagaMember);


        // Then comes saga event as trigger
        processedSagaMembers.add(SagaMemberClassMother.fromMember(sagaEventAsTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertTrue(sagaEventTriggeredSagaHandler.processAndCheckIfFinishedWith(sagaEventAsTrigger));
        shouldHaveSaved(sagaEventAsTriggerSagaMember);
    }

    @Test
    void saga_event_triggered___trigger_event_with_saga_info_should_not_be_processed() {

        OneSagaDomainEvent eventTriggerThatIsNonTriggerForAnotherSaga = OneSagaDomainEventMother.randomWithSagaInfo();

        repositoryIsGoingToReturnNoProcessedMembers();

        assertFalse(sagaEventTriggeredSagaHandler.processAndCheckIfFinishedWith(eventTriggerThatIsNonTriggerForAnotherSaga));
        shouldHaveSavedNothing();

    }

    @Test
    void saga_event_triggered___non_trigger_event_without_saga_info_should_not_be_processed() {

        AnotherSagaDomainEvent nonEventTriggerThatIsTriggerForAnotherSaga = AnotherSagaDomainEventMother.randomWithoutSagaInfo();

        repositoryIsGoingToReturnNoProcessedMembers();

        assertFalse(sagaEventTriggeredSagaHandler.processAndCheckIfFinishedWith(nonEventTriggerThatIsTriggerForAnotherSaga));
        shouldHaveSavedNothing();


    }

    @Test
    void saga_event_triggered___should_throw_exceptions_when_not_correct_objects_processed() {

        NotTriggerForThisSagaCommand notTriggerSagaCommand = NotTriggerCommandMother.random();
        assertThrows(NotATriggerForThisSaga.class,
                () -> sagaEventTriggeredSagaHandler.processAndCheckIfFinishedWith(notTriggerSagaCommand));

        RegularDomainEvent regularDomainEvent = RegularDomainEventMother.random();
        assertThrows(NotATriggerForThisSaga.class,
                () -> sagaEventTriggeredSagaHandler.processAndCheckIfFinishedWith(regularDomainEvent));

        ThirdSagaDomainEvent sagaDomainEventFromAnotherSaga = ThirdSagaDomainEventMother.randomWithSagaInfo();
        assertThrows(NotADomainEventForThisSaga.class,
                () -> sagaEventTriggeredSagaHandler.processAndCheckIfFinishedWith(sagaDomainEventFromAnotherSaga));

        ThirdSagaDomainEvent sagaDomainEventTriggerFromAnotherSaga = ThirdSagaDomainEventMother.randomWithoutSagaInfo();
        assertThrows(NotADomainEventForThisSaga.class,
                () -> sagaEventTriggeredSagaHandler.processAndCheckIfFinishedWith(sagaDomainEventTriggerFromAnotherSaga));


    }

}
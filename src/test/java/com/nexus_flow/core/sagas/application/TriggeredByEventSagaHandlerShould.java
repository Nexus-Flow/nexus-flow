package com.nexus_flow.core.sagas.application;

import com.nexus_flow.core.sagas.SagaHandlerUnitTestCase;
import com.nexus_flow.core.sagas.application.test_saga_handlers.TriggeredByEventSagaHandler;
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

class TriggeredByEventSagaHandlerShould extends SagaHandlerUnitTestCase {

    @Mock
    private TriggeredByEventSagaHandler eventTriggeredSagaHandler;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        eventTriggeredSagaHandler = new TriggeredByEventSagaHandler(repository);
    }

    @Test
    void event_triggered___first_trigger_then_event___finished_saga_ok() {

        // Given
        TriggerDomainEvent eventTrigger = TriggerDomainEventMother.random();
        SagaMember eventTriggerSagaMember = SagaMemberMother
                .fromEventTrigger(eventTrigger, eventTriggeredSagaHandler.getSagaType());

        OneSagaDomainEvent eventNotTrigger = OneSagaDomainEventMother.from(eventTrigger);
        SagaMember eventNotTriggerSagaMember = SagaMemberMother
                .fromNotTriggerEvent(eventNotTrigger, eventTriggeredSagaHandler.getSagaType());

        List<SagaMemberClass> processedSagaMembers = new ArrayList<>();

        // First comes event trigger
        processedSagaMembers.add(SagaMemberClassMother.fromMember(eventTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertFalse(eventTriggeredSagaHandler.processAndCheckIfFinishedWith(eventTrigger));
        shouldHaveSaved(eventTriggerSagaMember);


        // Then comes the not trigger saga event
        processedSagaMembers.add(SagaMemberClassMother.fromMember(eventNotTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertTrue(eventTriggeredSagaHandler.processAndCheckIfFinishedWith(eventNotTrigger));
        shouldHaveSaved(eventNotTriggerSagaMember);

    }

    @Test
    void event_triggered___first_not_trigger_then_trigger___finished_saga_ok() {

        TriggerDomainEvent eventTrigger = TriggerDomainEventMother.random();
        SagaMember eventTriggerSagaMember = SagaMemberMother
                .fromEventTrigger(eventTrigger, eventTriggeredSagaHandler.getSagaType());

        OneSagaDomainEvent eventNotTrigger = OneSagaDomainEventMother.from(eventTrigger);
        SagaMember eventNotTriggerSagaMember = SagaMemberMother
                .fromNotTriggerEvent(eventNotTrigger, eventTriggeredSagaHandler.getSagaType());

        List<SagaMemberClass> processedSagaMembers = new ArrayList<>();

        // First comes the not trigger saga event
        processedSagaMembers.add(SagaMemberClassMother.fromMember(eventNotTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertFalse(eventTriggeredSagaHandler.processAndCheckIfFinishedWith(eventNotTrigger));
        shouldHaveSaved(eventNotTriggerSagaMember);

        // Then comes event trigger
        processedSagaMembers.add(SagaMemberClassMother.fromMember(eventTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertTrue(eventTriggeredSagaHandler.processAndCheckIfFinishedWith(eventTrigger));
        shouldHaveSaved(eventTriggerSagaMember);

    }

    @Test
    void event_triggered___should_throw_exceptions_when_not_correct_objects_processed() {

        NotTriggerForThisSagaCommand notTriggerSagaCommand = NotTriggerCommandMother.random();
        assertThrows(NotATriggerForThisSaga.class,
                () -> eventTriggeredSagaHandler.processAndCheckIfFinishedWith(notTriggerSagaCommand));


        RegularDomainEvent regularDomainEvent = RegularDomainEventMother.random();
        assertThrows(NotATriggerForThisSaga.class,
                () -> eventTriggeredSagaHandler.processAndCheckIfFinishedWith(regularDomainEvent));

        AnotherSagaDomainEvent anotherSagaDomainEvent = AnotherSagaDomainEventMother.random();
        assertThrows(NotADomainEventForThisSaga.class,
                () -> eventTriggeredSagaHandler.processAndCheckIfFinishedWith(anotherSagaDomainEvent));


    }


}
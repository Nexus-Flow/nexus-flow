package com.nexus_flow.core.sagas.application;

import com.nexus_flow.core.sagas.SagaHandlerUnitTestCase;
import com.nexus_flow.core.sagas.application.test_saga_handlers.TriggeredByCommandSagaHandler;
import com.nexus_flow.core.sagas.domain.*;
import com.nexus_flow.core.sagas.domain.exceptions.NotADomainEventForThisSaga;
import com.nexus_flow.core.sagas.domain.exceptions.NotATriggerForThisSaga;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaMemberClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

class TriggeredByCommandSagaHandlerShould extends SagaHandlerUnitTestCase {

    private TriggeredByCommandSagaHandler commandTriggeredSagaHandler;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        commandTriggeredSagaHandler = new TriggeredByCommandSagaHandler(repository);
    }

    @Test
    void command_triggered___first_command_then_event___finished_saga_ok() {

        // Given
        OneSagaCommand commandTrigger = OneSagaCommandMother.random();
        SagaMember commandTriggerSagaMember = SagaMemberMother
                .fromCommandTrigger(commandTrigger, commandTriggeredSagaHandler.getSagaType());

        OneSagaDomainEvent eventNotTrigger = OneSagaDomainEventMother.from(commandTrigger);
        SagaMember eventNotTriggerSagaMember = SagaMemberMother
                .fromNotTriggerEvent(eventNotTrigger, commandTriggeredSagaHandler.getSagaType());

        List<SagaMemberClass> processedSagaMembers = new ArrayList<>();

        // First comes command
        processedSagaMembers.add(SagaMemberClassMother.fromMember(commandTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertFalse(commandTriggeredSagaHandler.processAndCheckIfFinishedWith(commandTrigger));
        shouldHaveSaved(commandTriggerSagaMember);


        // Then comes event
        processedSagaMembers.add(SagaMemberClassMother.fromMember(eventNotTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertTrue(commandTriggeredSagaHandler.processAndCheckIfFinishedWith(eventNotTrigger));
        shouldHaveSaved(eventNotTriggerSagaMember);

    }

    @Test
    void command_triggered___first_event_then_command___finished_saga_ok() {

        // Given 
        OneSagaCommand commandTrigger = OneSagaCommandMother.random();
        SagaMember commandTriggerSagaMember = SagaMemberMother
                .fromCommandTrigger(commandTrigger, commandTriggeredSagaHandler.getSagaType());

        OneSagaDomainEvent eventNotTrigger = OneSagaDomainEventMother.from(commandTrigger);
        SagaMember eventNotTriggerSagaMember = SagaMemberMother
                .fromNotTriggerEvent(eventNotTrigger, commandTriggeredSagaHandler.getSagaType());

        List<SagaMemberClass> processedSagaMembers = new ArrayList<>();

        // First comes not trigger event
        processedSagaMembers.add(SagaMemberClassMother.fromMember(eventNotTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertFalse(commandTriggeredSagaHandler.processAndCheckIfFinishedWith(eventNotTrigger));
        shouldHaveSaved(eventNotTriggerSagaMember);


        // Then comes command
        processedSagaMembers.add(SagaMemberClassMother.fromMember(commandTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertTrue(commandTriggeredSagaHandler.processAndCheckIfFinishedWith(commandTrigger));
        shouldHaveSaved(commandTriggerSagaMember);
    }

    @Test
    void command_triggered___first_command_then_event_from_another_saga___two_sagas_not_finishes() {

        OneSagaCommand commandTrigger = OneSagaCommandMother.random();
        SagaMember commandTriggerSagaMember = SagaMemberMother
                .fromCommandTrigger(commandTrigger, commandTriggeredSagaHandler.getSagaType());

        // First comes command
        List<SagaMemberClass> processedSagaMembers = new ArrayList<>();
        processedSagaMembers.add(SagaMemberClassMother.fromMember(commandTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(processedSagaMembers);

        assertFalse(commandTriggeredSagaHandler.processAndCheckIfFinishedWith(commandTrigger));
        shouldHaveSaved(commandTriggerSagaMember);


        // Then comes event from another saga
        List<SagaMemberClass> anotherProcessedSagaMembers = new ArrayList<>();
        OneSagaDomainEvent    anotherSagaDomainEvent      = OneSagaDomainEventMother.randomWithSagaInfo();
        SagaMember anotherSagaNotTriggerSagaMember = SagaMemberMother
                .fromNotTriggerEvent(anotherSagaDomainEvent, commandTriggeredSagaHandler.getSagaType());

        anotherProcessedSagaMembers.add(SagaMemberClassMother.fromMember(anotherSagaNotTriggerSagaMember));

        repositoryIsGoingToReturnProcessedMembers(anotherProcessedSagaMembers);

        assertFalse(commandTriggeredSagaHandler.processAndCheckIfFinishedWith(anotherSagaDomainEvent));
        verify(repository, Mockito.times(1)).saveSagaMember(Mockito.any());

    }

    @Test
    void command_triggered___should_throw_exceptions_when_not_correct_objects_processed() {

        NotTriggerForThisSagaCommand notTriggerSagaCommand = NotTriggerCommandMother.random();
        assertThrows(NotATriggerForThisSaga.class,
                () -> commandTriggeredSagaHandler.processAndCheckIfFinishedWith(notTriggerSagaCommand));

        RegularDomainEvent regularDomainEvent = RegularDomainEventMother.random();
        assertThrows(NotATriggerForThisSaga.class,
                () -> commandTriggeredSagaHandler.processAndCheckIfFinishedWith(regularDomainEvent));

        AnotherSagaDomainEvent anotherSagaDomainEvent = AnotherSagaDomainEventMother.random();
        assertThrows(NotADomainEventForThisSaga.class,
                () -> commandTriggeredSagaHandler.processAndCheckIfFinishedWith(anotherSagaDomainEvent));

    }


}
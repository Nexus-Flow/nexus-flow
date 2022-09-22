package com.nexus_flow.core.infrastructure;


import com.nexus_flow.core.cqrs.domain.query.Query;
import com.nexus_flow.core.cqrs.domain.query.QueryBus;
import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.ddd.exceptions.DomainError;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.messaging.domain.EventBus;
import com.nexus_flow.core.sagas.application.SagaHandler;
import com.nexus_flow.core.sagas.domain.SagaCommand;
import com.nexus_flow.core.sagas.domain.SagaRepository;
import com.nexus_flow.core.utils.uuid_generator.UuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;

@Tag("unit")
public abstract class UnitTestCase {

    protected EventBus       eventBus;
    protected QueryBus       queryBus;
    protected UuidGenerator  uuidGenerator;
    protected SagaRepository sagaRepository;

    @BeforeEach
    protected void setUp() {
        eventBus       = mock(EventBus.class);
        queryBus       = mock(QueryBus.class);
        uuidGenerator  = mock(UuidGenerator.class);
        sagaRepository = mock(SagaRepository.class);
    }

    public void shouldHavePublished(List<DomainEvent> domainEvents) {
        verify(eventBus, atLeastOnce()).publish(domainEvents);
    }

    public void shouldHavePublished(DomainEvent domainEvent) {
        shouldHavePublished(Collections.singletonList(domainEvent));
    }

    public void uuidGeneratorIsGoingToReturn(String uuid) {
        when(uuidGenerator.generate()).thenReturn(uuid);
    }

    public void uuidGeneratorIsGoingToReturnOnSeveralCalls(String uuid, String... others) {
        when(uuidGenerator.generate()).thenReturn(uuid, others);
    }

    public void queryBusIsGoingToReturnGivenResponse(Query query, Response response) {
        when(queryBus.ask(query)).thenReturn(response);
    }

    public void queryBusIsGoingToReturnGivenAuditResponse(Query query, Object response) {
        when(queryBus.ask(query)).thenReturn(response);
    }

    public void queryBusIsGoingToThrowDomainError(Query query, DomainError error) {
        when(queryBus.ask(query)).thenThrow(error);
    }

    public void sagaHandlerBlockerIsGoingToDoNothing(SagaHandler sagaHandler) {
        Mockito.doNothing().when(sagaHandler).processAndBlockUntilFinished(any(DomainEvent.class));
        Mockito.doNothing().when(sagaHandler).processAndBlockUntilFinished(any(SagaCommand.class));
    }

    public void sagaHandlerProcessorIsGoingToReturnFinished(SagaHandler sagaHandler) {
        Mockito.doReturn(true).when(sagaHandler).processAndCheckIfFinishedWith(any(DomainEvent.class));
        Mockito.doReturn(true).when(sagaHandler).processAndCheckIfFinishedWith(any(SagaCommand.class));
    }

    public void sagaHandlerProcessorIsGoingToReturnNotFinished(SagaHandler sagaHandler) {
        Mockito.doReturn(false).when(sagaHandler).processAndCheckIfFinishedWith(any(DomainEvent.class));
        Mockito.doReturn(false).when(sagaHandler).processAndCheckIfFinishedWith(any(SagaCommand.class));
    }


}

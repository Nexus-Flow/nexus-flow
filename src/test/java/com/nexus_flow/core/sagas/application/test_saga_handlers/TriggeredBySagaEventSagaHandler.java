package com.nexus_flow.core.sagas.application.test_saga_handlers;

import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.sagas.application.SagaHandler;
import com.nexus_flow.core.sagas.domain.AnotherSagaDomainEvent;
import com.nexus_flow.core.sagas.domain.OneSagaDomainEvent;
import com.nexus_flow.core.sagas.domain.SagaCommand;
import com.nexus_flow.core.sagas.domain.SagaRepository;
import com.nexus_flow.core.sagas.domain.value_objects.SagaType;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.OtherInvolvedEventsClasses;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.SagaName;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.TimeOut;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.TriggeredBy;

import java.time.Duration;
import java.util.Collections;

public class TriggeredBySagaEventSagaHandler extends SagaHandler {

    public TriggeredBySagaEventSagaHandler(SagaRepository repository) {
        super(new SagaType(
                new SagaName("Test saga with a Saga Domain Event as a trigger."),
                new TriggeredBy(OneSagaDomainEvent.class),
                new OtherInvolvedEventsClasses(Collections.singletonList(AnotherSagaDomainEvent.class)),
                new TimeOut(Duration.ofSeconds(5))
        ), repository);
    }

    public void handle(SagaCommand command) {
        processAndBlockUntilFinished(command);
    }


    public void on(DomainEvent event) {
        processAndCheckIfFinishedWith(event);
    }
}

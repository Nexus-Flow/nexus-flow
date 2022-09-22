package com.nexus_flow.core.sagas.domain.exceptions;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.exceptions.DomainError;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaMemberClass;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaTriggerId;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaTriggeredOn;
import com.nexus_flow.core.sagas.domain.value_objects.saga_type.SagaName;

import java.util.List;
import java.util.stream.Collectors;

public class SagaTimeOut extends DomainError {

    public SagaTimeOut(SagaName name, SagaTriggerId triggerId, SagaTriggeredOn triggeredOn, List<SagaMemberClass> sagaMemberClasses) {
        super("saga_timed_out",
                "Saga " + name.getValue() + " <" + triggerId.getValue()
                        + "> triggered on " + Utils.dateToString(triggeredOn.getValue()) + " has timed out. " +
                        "It could lead to inconsistency, because these events may not have been processed: " +
                        sagaMemberClasses.stream()
                                .map(sagaMemberClass ->
                                        Utils.toSnake(sagaMemberClass.getValue().getSimpleName()).replaceAll("_", " "))
                                .collect(Collectors.joining(", ")));

    }
}


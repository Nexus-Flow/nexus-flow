package com.nexus_flow.core.sagas.domain.exceptions;

import com.nexus_flow.core.ddd.exceptions.DomainError;
import com.nexus_flow.core.sagas.domain.value_objects.SagaType;

public class NotATriggerForThisSaga extends DomainError {
    public NotATriggerForThisSaga(SagaType sagaType, Class<?> supposedTrigger) {
        super("not_a_trigger_for_this_saga",
                supposedTrigger.getSimpleName() + "is not a trigger for saga " + sagaType.getName().getValue()
                        + ", which is triggered by " + sagaType.getTriggeredBy().getValue().getSimpleName());
    }


}

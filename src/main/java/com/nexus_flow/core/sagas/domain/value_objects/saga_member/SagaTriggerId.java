package com.nexus_flow.core.sagas.domain.value_objects.saga_member;

import com.nexus_flow.core.ddd.value_objects.StringValueObject;

public class SagaTriggerId extends StringValueObject {

    public SagaTriggerId(String value) {
        super(value);
        checkNotNull();
    }

    private SagaTriggerId() {
        super();
    }

}

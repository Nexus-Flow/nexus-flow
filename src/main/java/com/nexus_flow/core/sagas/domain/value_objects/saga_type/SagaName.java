package com.nexus_flow.core.sagas.domain.value_objects.saga_type;

import com.nexus_flow.core.ddd.value_objects.StringValueObject;

public class SagaName extends StringValueObject {
    public SagaName(String value) {
        super(value);
        checkNotNull();
    }

    private SagaName() {
    }
}

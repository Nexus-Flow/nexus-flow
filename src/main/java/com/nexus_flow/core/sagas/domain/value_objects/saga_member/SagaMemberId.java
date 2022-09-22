package com.nexus_flow.core.sagas.domain.value_objects.saga_member;

import com.nexus_flow.core.ddd.value_objects.StringValueObject;

public class SagaMemberId extends StringValueObject {

    public SagaMemberId(String value) {
        super(value);
        checkNotNull();
    }

    private SagaMemberId() {
        super();
    }

}

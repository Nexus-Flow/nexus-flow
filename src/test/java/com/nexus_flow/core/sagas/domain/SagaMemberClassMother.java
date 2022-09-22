package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaMemberClass;

public class SagaMemberClassMother {

    public static SagaMemberClass create(Class<?> sagaMemberClass) {
        return new SagaMemberClass(sagaMemberClass);
    }

    public static SagaMemberClass fromMember(SagaMember sagaMember) {
        return create(sagaMember.getSagaMemberClass().getValue());
    }

}

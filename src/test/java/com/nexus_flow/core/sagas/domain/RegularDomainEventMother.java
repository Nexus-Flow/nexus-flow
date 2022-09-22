package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.domain.UuidMother;

public class RegularDomainEventMother {

    public static RegularDomainEvent create(String aggregateId) {
        return new RegularDomainEvent(aggregateId);
    }

    public static RegularDomainEvent random() {
        return create(UuidMother.random());
    }

}

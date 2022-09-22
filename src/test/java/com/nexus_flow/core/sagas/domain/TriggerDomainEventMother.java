package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.domain.UuidMother;

public class TriggerDomainEventMother {

    public static TriggerDomainEvent create(String aggregateId) {
        return new TriggerDomainEvent(aggregateId);
    }

    public static TriggerDomainEvent random() {
        return create(UuidMother.random());
    }

}

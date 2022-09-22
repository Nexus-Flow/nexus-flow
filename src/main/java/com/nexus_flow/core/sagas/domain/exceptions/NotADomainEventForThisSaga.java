package com.nexus_flow.core.sagas.domain.exceptions;

import com.nexus_flow.core.ddd.exceptions.DomainError;
import com.nexus_flow.core.messaging.domain.DomainEvent;

public class NotADomainEventForThisSaga extends DomainError {
    public NotADomainEventForThisSaga(DomainEvent incomingEvent) {
        super("not_domain_event_for_this_saga",
                incomingEvent.getClass().getSimpleName() + " is not a event involved in current saga.");
    }

}

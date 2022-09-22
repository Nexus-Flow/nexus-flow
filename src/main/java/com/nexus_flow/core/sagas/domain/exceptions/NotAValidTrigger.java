package com.nexus_flow.core.sagas.domain.exceptions;

import com.nexus_flow.core.ddd.exceptions.DomainError;
import com.nexus_flow.core.messaging.domain.DomainEvent;

public class NotAValidTrigger extends DomainError {
    public <T extends DomainEvent> NotAValidTrigger() {
        super("not_a_valid_trigger",
                "Trigger should be DomainEvent or a Command");
    }
}

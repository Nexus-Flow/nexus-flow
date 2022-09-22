package com.nexus_flow.core.sagas.domain.exceptions;

import com.nexus_flow.core.ddd.exceptions.DomainError;

public class NotASagaObject extends DomainError {
    public NotASagaObject(Class<?> incomingObject) {
        super("not_a_saga_object",
                incomingObject.getSimpleName() + " is not a saga object.");
    }
}

package com.nexus_flow.core.messaging.domain;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface DomainEventSubscriber {
    Class<? extends DomainEvent>[] value();
}

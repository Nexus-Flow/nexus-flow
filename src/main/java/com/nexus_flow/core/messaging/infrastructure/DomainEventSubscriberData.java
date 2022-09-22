package com.nexus_flow.core.messaging.infrastructure;


import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.messaging.domain.DomainEvent;

import java.util.List;

public final class DomainEventSubscriberData {

    private final Class<?>                           subscriberClass;
    private final List<Class<? extends DomainEvent>> subscribedEvents;
    private final String[]                           nameParts;

    public DomainEventSubscriberData(
            Class<?> subscriberClass,
            List<Class<? extends DomainEvent>> subscribedEvents
    ) {
        this.subscriberClass  = subscriberClass;
        this.subscribedEvents = subscribedEvents;
        this.nameParts        = subscriberClass.getName().split("\\.");
    }

    public Class<?> subscriberClass() {
        return subscriberClass;
    }

    public String companyName() {
        return nameParts[1];
    }

    public String projectName() {
        return nameParts[2];
    }

    public String contextName() {
        return nameParts[3];
    }

    public String moduleName() {
        return nameParts[4];
    }

    public String className() {
        return nameParts[nameParts.length - 1];
    }

    public List<Class<? extends DomainEvent>> subscribedEvents() {
        return subscribedEvents;
    }

    public String formatRabbitMqQueueName() {
        return String.format("%s.%s.%s", contextName(), moduleName(), Utils.toSnake(className()));
    }

    public String formatRabbitMqQueueNameWithCompany() {
        return String.format("%s.%s.%s.%s", companyName(), contextName(), moduleName(), Utils.toSnake(className()));
    }
}

package com.nexus_flow.core.messaging.infrastructure.spring;

import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.messaging.domain.EventBus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;


@Service(value = "spring-event-bus")
public class SpringApplicationEventBus implements EventBus {
    private final ApplicationEventPublisher publisher;

    public SpringApplicationEventBus(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(final List<DomainEvent> events) {
        events.forEach(this::publish);
    }

    private void publish(final DomainEvent event) {
        this.publisher.publishEvent(event);
    }
}

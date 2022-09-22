package com.nexus_flow.core.messaging.infrastructure.rabbitmq;

import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

@Service
@Log4j2
public class RabbitMqConsumerAutoStarter implements ApplicationListener<ApplicationReadyEvent> {

    private final RabbitMqDomainEventsConsumer rabbitMqDomainEventsConsumer;
    private final RabbitMQProps                rabbitMQProps;

    public RabbitMqConsumerAutoStarter(RabbitMqDomainEventsConsumer rabbitMqDomainEventsConsumer,
                                       RabbitMQProps rabbitMQProps) {
        this.rabbitMqDomainEventsConsumer = rabbitMqDomainEventsConsumer;
        this.rabbitMQProps                = rabbitMQProps;
    }


    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
        if (rabbitMQProps.getConsumerAutostart()) rabbitMqDomainEventsConsumer.consume();
    }
}

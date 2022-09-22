package com.nexus_flow.core.messaging.infrastructure.rabbitmq;

import com.nexus_flow.core.ddd.exceptions.CouldNotSerializeMap;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.messaging.domain.EventBus;
import com.nexus_flow.core.messaging.domain.EventCouldNotBeenPublishedDomainEvent;
import com.nexus_flow.core.messaging.infrastructure.postgres.PostgresEventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@Slf4j
@Service(value = "rabbitmq-event-bus")
public class RabbitMqEventBus implements EventBus {

    private final RabbitMqPublisher publisher;
    private final PostgresEventBus  failoverPublisher;
    private final RabbitMQProps     rabbitMQProps;
    List<DomainEvent> failedEvents = new ArrayList<>();

    @Value("${spring.application.name}")
    private String boundedContextName;

    public RabbitMqEventBus(RabbitMqPublisher publisher,
                            PostgresEventBus failoverPublisher,
                            RabbitMQProps rabbitMQProps) {
        this.publisher         = publisher;
        this.rabbitMQProps     = rabbitMQProps;
        this.failoverPublisher = failoverPublisher;

    }

    @Override
    public void publish(List<DomainEvent> events) {

        failedEvents.clear();

        events.forEach(this::publish);

        if (!failedEvents.isEmpty()) {
            failoverPublisher.publish(failedEvents);
        }
    }

    private void publish(DomainEvent domainEvent) {
        try {

            this.publisher.publish(domainEvent, rabbitMQProps.getExchange());

            String eventModuleAndAction = domainEvent.getEventName().split(".event.")[1]
                    .replaceAll("[_.]", " ")
                    .toUpperCase();

            log.info(eventModuleAndAction + ". AGGREGATE ID: <" + domainEvent.getAggregateId() + ">");

        } catch (AmqpException | CouldNotSerializeMap error) {

            domainEvent.failedToPublish();
            failedEvents.add(domainEvent);

            String errorMessage = "";

            if (domainEvent.getTimesWasTriedToPublish() <= 1) {
                errorMessage = message(domainEvent.getEventName(), domainEvent.getEventId(),
                        "could not be published to RabbitMQ",
                        "--> it will persisted to database");
            } else {
                errorMessage = message(domainEvent.getEventName(), domainEvent.getEventId(),
                        "could not be published to RabbitMQ",
                        "--> it will be remain in database") + "\n" +
                        ("It's the attempt number " + domainEvent.getTimesWasTriedToPublish() + " --> You should check what's wrong");
            }

            log.error(errorMessage);

            if (error instanceof CouldNotSerializeMap) {
                publish(Collections.singletonList(
                        new EventCouldNotBeenPublishedDomainEvent(
                                domainEvent.getEventId(),
                                domainEvent.getEventName(),
                                boundedContextName,
                                errorMessage,
                                boundedContextName)));
            }


        }
    }

    private String message(String eventName, String eventId, String whatHappened, String action) {
        return String.format("The message <%s> with id <%s> %s %s",
                eventName, eventId, whatHappened, action);
    }

}

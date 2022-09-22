package com.nexus_flow.core.messaging.infrastructure.rabbitmq;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.exceptions.CouldNotDeserializeMessage;
import com.nexus_flow.core.ddd.exceptions.NoCorrectOrderForEvent;
import com.nexus_flow.core.messaging.domain.*;
import com.nexus_flow.core.messaging.infrastructure.DomainEventJsonDeserializer;
import com.nexus_flow.core.messaging.infrastructure.DomainEventSubscribersCollector;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.*;


@Service(value = "message-broker-consumer")
@Slf4j
public final class RabbitMqDomainEventsConsumer implements DomainEventsConsumer {
    public static final  String                          UNKNOWN                = "unknown";
    private static final String                          CONSUMER_NAME          = "domain_events_consumer";
    private final        RabbitListenerEndpointRegistry  registry;
    private final        RabbitMQProps                   rabbitMQProps;
    private final        DomainEventJsonDeserializer     deserializer;
    private final        ApplicationContext              context;
    private final        RabbitMqPublisher               publisher;
    private final        HashMap<String, Object>         domainEventSubscribers = new HashMap<>();
    private final EventBus eventBus;
    private final        DomainEventSubscribersCollector domainEventSubscribersCollector;

    @Value("${spring.application.name}")
    private String boundedContextName;

    public RabbitMqDomainEventsConsumer(
            RabbitMQProps rabbitMQProps, RabbitListenerEndpointRegistry registry,
            DomainEventSubscribersCollector domainEventSubscribersCollector,
            DomainEventJsonDeserializer deserializer,
            ApplicationContext context,
            RabbitMqPublisher publisher, EventBus eventBus) {
        this.rabbitMQProps                   = rabbitMQProps;
        this.registry                        = registry;
        this.domainEventSubscribersCollector = domainEventSubscribersCollector;
        this.deserializer                    = deserializer;
        this.context                         = context;
        this.publisher                       = publisher;
        this.eventBus                        = eventBus;
    }

    @Override
    public void consume() {
        getConsumerContainer().start();
        log.info("RabbitMQ consumer has been started.");
    }

    @Override
    public void stop() {
        getConsumerContainer().stop();
        log.warn("***********************************");
        log.warn("RabbitMQ consumer has been stopped!");
        log.warn("***********************************");
    }

    private AbstractMessageListenerContainer getConsumerContainer() {
        AbstractMessageListenerContainer container = (AbstractMessageListenerContainer) registry
                .getListenerContainer(CONSUMER_NAME);

        container.addQueueNames(domainEventSubscribersCollector.rabbitMqFormattedNames());
        return container;
    }

    @RabbitListener(id = CONSUMER_NAME, autoStartup = "false")
    public void consumer(Message message) {

        String      queue       = message.getMessageProperties().getConsumerQueue();
        DomainEvent domainEvent = null;

        try {
            String serializedMessage = new String(message.getBody());
            domainEvent = deserializer.deserialize(serializedMessage);

            Object subscriber = domainEventSubscribers.containsKey(queue)
                    ? domainEventSubscribers.get(queue)
                    : subscriberFor(queue);

            Method subscriberOnMethod = subscriber.getClass().getMethod("on", domainEvent.getClass());

            subscriberOnMethod.invoke(subscriber, domainEvent);

            log.info(
                    Utils.toSnake(subscriber.getClass().getSimpleName().split("On")[0]).replaceAll("[_]", " ")
                            + " FROM EVENT " + domainEvent.getEventName().split(".event.")[1]
                            + " WITH AGGREGATE ID <" + domainEvent.getAggregateId() + ">"
            );

        } catch (NoSuchMethodException e) {
            String errorMessage = String.format(
                    "The subscriber <%s> should implement a method `on` listening the domain event <%s>",
                    queue,
                    Objects.requireNonNull(domainEvent).getEventName()
            );
            log.error(errorMessage);
            handleConsumptionError(message, domainEvent, queue, errorMessage);

        } catch (CouldNotDeserializeMessage couldNotDeserializeMessage) {
            String errorMessage = "The message could not be deserialized (it may indicate wrong format): "
                    + couldNotDeserializeMessage.getMessage();
            log.error(errorMessage);
            handleConsumptionError(message, null, queue, errorMessage);

        } catch (Exception e) {
            if (e.getCause() instanceof NoCorrectOrderForEvent) {
                handleConsumptionWrongOrder(message, domainEvent, queue, e.getCause().getMessage());
            } else {
                handleConsumptionError(message, domainEvent, queue, e.getCause().toString());
            }
        }

    }

    private void handleConsumptionWrongOrder(Message message, DomainEvent domainEvent, String queue, String errorMessage) {

        Optional<DomainEvent> event = Optional.ofNullable(domainEvent);

        if (hasBeenRedeliveredWrongOrderTooMuch(message)) {
            sendToDeadLetterRedeliveredTooMuch(message, queue, errorMessage, event);
        } else {
            log.warn("Message <{}> with id <{}> could not be processed due to the event came in the wrong order: {} ==> attempt {} of {}",
                    event.map(DomainEvent::getEventName).orElse(UNKNOWN),
                    event.map(DomainEvent::getEventId).orElse(UNKNOWN),
                    errorMessage,
                    getNextWrongOrderErrorsCount(message),
                    rabbitMQProps.getMaxWrongOrderAttempts());
            sendToRetry(message, queue, RedeliveryType.WRONG_ORDER, errorMessage);
        }
    }


    private void handleConsumptionError(Message message, DomainEvent domainEvent, String queue, String errorMessage) {

        Optional<DomainEvent> event = Optional.ofNullable(domainEvent);

        if (hasBeenRedeliveredTooMuch(message)) {
            sendToDeadLetterRedeliveredTooMuch(message, queue, errorMessage, event);
        } else {
            log.warn("Message <{}> with id <{}> could not be processed due to: {} ==> attempt {} of {}",
                    event.map(DomainEvent::getEventName).orElse(UNKNOWN),
                    event.map(DomainEvent::getEventId).orElse(UNKNOWN),
                    errorMessage,
                    getNextErrorsCount(message),
                    rabbitMQProps.getMaxAttempts());
            sendToRetry(message, queue, RedeliveryType.ERROR, errorMessage);
        }
    }

    private void sendToRetry(Message message, String queue, RedeliveryType redeliveryType, String redeliveryMessage) {
        sendMessageTo(
                RabbitMqExchangeNameFormatter.retry(rabbitMQProps.getExchange()),
                message,
                queue,
                redeliveryType,
                redeliveryMessage);
    }

    private void sendToDeadLetterRedeliveredTooMuch(Message message, String queue, String errorMessage, Optional<DomainEvent> event) {
        log.error("Message <{}>> with id <{}> could not be processed due to: {} ==> sending to dead letter",
                event.map(DomainEvent::getEventName).orElse(UNKNOWN),
                event.map(DomainEvent::getEventId).orElse(UNKNOWN),
                errorMessage);
        sendToDeadLetter(message, queue, errorMessage);
        eventBus.publish(Collections.singletonList(
                new EventCouldNotBeenConsumedDomainEvent(
                        event.map(DomainEvent::getEventId).orElse(UNKNOWN),
                        event.map(DomainEvent::getEventName).orElse(UNKNOWN),
                        queue,
                        errorMessage,
                        boundedContextName)));
    }

    private void sendToDeadLetter(Message message, String queue, String errorMessage) {
        sendMessageTo(
                RabbitMqExchangeNameFormatter.deadLetter(rabbitMQProps.getExchange()),
                message,
                queue,
                RedeliveryType.ERROR,
                errorMessage);
    }

    private void sendMessageTo(String exchange,
                               Message message,
                               String queue,
                               RedeliveryType redeliveryType,
                               String errorMessage) {

        Map<String, Object> headers = message.getMessageProperties().getHeaders();

        int nextDeliveryCount = getNextRedeliveryCount(message);
        headers.put("redelivery_" + nextDeliveryCount + "_" + redeliveryType, errorMessage);
        headers.put("redelivery_count", getNextRedeliveryCount(message));

        if (redeliveryType.equals(RedeliveryType.WRONG_ORDER)) {
            int nextWrongOrderErrorsCount = getNextWrongOrderErrorsCount(message);
            headers.put("wrong_order_errors_count", nextWrongOrderErrorsCount);
        }

        if (redeliveryType.equals(RedeliveryType.ERROR)) {
            int nextErrorsCount = getNextErrorsCount(message);
            headers.put("errors_count", nextErrorsCount);
        }

        headers.put("x-death", rabbitMQProps.getMaxAttempts());

        MessageBuilder.fromMessage(message).andProperties(
                MessagePropertiesBuilder.newInstance()
                        .setContentEncoding("utf-8")
                        .setContentType("application/json")
                        .copyHeaders(headers)
                        .build());

        publisher.publish(message, exchange, queue);
    }

    private boolean hasBeenRedeliveredTooMuch(Message message) {
        return getNextErrorsCount(message) > rabbitMQProps.getMaxAttempts();
    }

    private boolean hasBeenRedeliveredWrongOrderTooMuch(Message message) {
        return getNextWrongOrderErrorsCount(message) > rabbitMQProps.getMaxWrongOrderAttempts();
    }

    private int getNextRedeliveryCount(Message message) {
        return (int) message.getMessageProperties().getHeaders()
                .getOrDefault("redelivery_count", 0) + 1;
    }

    private int getNextErrorsCount(Message message) {
        return (int) message.getMessageProperties().getHeaders()
                .getOrDefault("errors_count", 0) + 1;
    }

    private int getNextWrongOrderErrorsCount(Message message) {
        return (int) message.getMessageProperties().getHeaders()
                .getOrDefault("wrong_order_errors_count", 0) + 1;
    }

    @SneakyThrows
    private Object subscriberFor(String queue) {
        String[] queueParts     = queue.split("\\.");
        String   subscriberName = Utils.toCamelFirstLower(queueParts[queueParts.length - 1]);

        try {

            Object subscriber = context.getBean(subscriberName);
            domainEventSubscribers.put(queue, subscriber);
            return subscriber;

        } catch (Exception e) {
            throw new NoSubscribersRegistered(String.format("There are not registered subscribers for <%s> queue", queue));
        }
    }

    enum RedeliveryType {

        ERROR, WRONG_ORDER;

        @Override
        public String toString() {
            return this.name().toLowerCase();
        }
    }

}

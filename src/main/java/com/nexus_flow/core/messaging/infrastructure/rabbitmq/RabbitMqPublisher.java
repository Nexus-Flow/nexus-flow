package com.nexus_flow.core.messaging.infrastructure.rabbitmq;

import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import com.nexus_flow.core.ddd.exceptions.CouldNotSerializeMap;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.messaging.infrastructure.DomainEventJsonSerializer;
import com.nexus_flow.core.messaging.infrastructure.postgres.PublisherConnectionListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Optional;

@NexusFlowService
@Slf4j
public final class RabbitMqPublisher {

    private final RabbitTemplate              rabbitTemplate;
    private final PublisherConnectionListener publisherConnectionListener;
    private final RabbitMQProps               rabbitMQProps;

    public RabbitMqPublisher(RabbitTemplate rabbitTemplate,
                             PublisherConnectionListener publisherConnectionListener, RabbitMQProps rabbitMQProps) {
        this.rabbitTemplate              = rabbitTemplate;
        this.publisherConnectionListener = publisherConnectionListener;
        this.rabbitMQProps               = rabbitMQProps;
        addConnectionListener();
    }


    public void publish(DomainEvent domainEvent, String exchangeName) throws AmqpException, CouldNotSerializeMap {
        String serializedDomainEvent = DomainEventJsonSerializer.serialize(domainEvent);

        Message message = new Message(
                serializedDomainEvent.getBytes(),
                MessagePropertiesBuilder.newInstance()
                        .setContentEncoding("utf-8")
                        .setContentType("application/json")
                        .build()
        );

        rabbitTemplate.send(exchangeName, domainEvent.getEventName(), message);
    }

    public void publish(Message domainEvent, String exchangeName, String routingKey) throws AmqpException {
        rabbitTemplate.send(exchangeName, routingKey, domainEvent);
    }

    private void addConnectionListener() {
        rabbitTemplate.getConnectionFactory()
                .addConnectionListener(publisherConnectionListener);
    }

    public boolean checkRabbitMQIsRunning() {

        // In order to interfere 'real' connections with a test connection as less as possible
        // Because when connection listener is added again, it still detects test connection
        removeConnectionListener();

        boolean isRunning = connectionTest();

        addConnectionListener();

        return isRunning;
    }

    private boolean connectionTest() {
        boolean isRunning;

        String virtualHost = Optional.ofNullable(rabbitMQProps.getVirtualHost()).orElse("/");

        try {

            rabbitTemplate.execute(channel -> {
                try {
                    // Marks this connection with an id in order to ignore it in our connection listener
                    channel.getConnection().setId("test_connection_to_rabbitmq");
                    channel.exchangeDeclarePassive(rabbitMQProps.getExchange());
                    channel.abort();
                    log.info("RabbitMQ is running. Connected to virtual host " + virtualHost);
                    return null;

                } catch (Exception e) {
                    log.error("RabbitMQ is running. Connected to virtual host " + virtualHost
                            + " But exchange {} does not exist!", rabbitMQProps.getExchange());
                    log.error("*******************************************************");
                    log.error("* This application will not be able to publish events *");
                    log.error("*******************************************************");
                    channel.abort();
                    return null;
                }
            });
            isRunning = true;

        } catch (Exception e) {
            log.error("****************************");
            log.error("* RabbitMQ is not started! *");
            log.error("****************************");
            isRunning = false;
        }

        return isRunning;
    }

    private void removeConnectionListener() {
        rabbitTemplate.getConnectionFactory().removeConnectionListener(publisherConnectionListener);
    }

}

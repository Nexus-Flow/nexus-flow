package com.nexus_flow.core.messaging.infrastructure.postgres;

import com.nexus_flow.core.messaging.infrastructure.PublishSupervisor;
import com.rabbitmq.client.ShutdownSignalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Slf4j
public class PublisherConnectionListener implements ConnectionListener {

    private final PublishSupervisor publishSupervisor;


    public PublisherConnectionListener(PublishSupervisor publishSupervisor) {
        this.publishSupervisor = publishSupervisor;
    }

    @Override
    public void onClose(Connection connection) {
        publishSupervisor.stopEventDatabaseConsumer();
    }

    @Override
    public void onShutDown(ShutdownSignalException signal) {
        publishSupervisor.stopEventDatabaseConsumer();
    }

    @Override
    public void onCreate(Connection connection) {
        // If the connection created is the one from our test of RabbitMQ connectivity, we ignore it
        String connectionId = Optional.ofNullable(connection.getDelegate())
                .map(com.rabbitmq.client.Connection::getId)
                .orElse("new_connection");
        if (!connectionId.equalsIgnoreCase("test_connection_to_rabbitmq")) {
            publishSupervisor.newConnectionCreated();
        }
    }
}

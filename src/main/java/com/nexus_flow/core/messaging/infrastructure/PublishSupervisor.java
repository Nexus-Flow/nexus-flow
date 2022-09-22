package com.nexus_flow.core.messaging.infrastructure;

import com.nexus_flow.core.ddd.exceptions.RetrievingEventFromDatabaseError;
import com.nexus_flow.core.messaging.infrastructure.postgres.PostgresDomainEventsConsumer;
import com.nexus_flow.core.messaging.infrastructure.rabbitmq.RabbitMqPublisher;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PublishSupervisor implements ApplicationListener<ApplicationReadyEvent> {

    private RabbitMqPublisher rabbitMqPublisher;
    private PostgresDomainEventsConsumer postgresDomainEventsConsumer;
    private ApplicationContext           applicationContext;
    private boolean                      supervisedBefore           = false;
    private boolean                      connectionWasRunningBefore = false;


    @SneakyThrows
    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {

        applicationContext = applicationReadyEvent.getApplicationContext();

        this.postgresDomainEventsConsumer =
                applicationContext.getBean(PostgresDomainEventsConsumer.class);

        this.rabbitMqPublisher =
                applicationContext.getBean(RabbitMqPublisher.class);

        checkStatusPublish();

        statusChecked();
    }

    @SneakyThrows
    public void newConnectionCreated() {
        // If connection was running before means that connection was lost while application was running
        if (connectionWasRunningBefore()) {
            log.info("It looks that RabbitMQ connexion is back!");
            checkStatusPublish();
        } else {
            connectionRunning();
            // If status hasn't been checked before means application is starting
            // --> no need to check status, because it will be checked when application starts
            if (statusHasBeenCheckedBefore()) {
                log.info("It looks that RabbitMQ connexion is back!");
                checkStatusPublish();
            }
        }

    }

    public void checkStatusPublish() throws RetrievingEventFromDatabaseError {


        boolean rabbitMQIsRunning                  = rabbitMQIsRunning();
        boolean existEventsInDatabaseToBePublished = existEventsInDatabaseToBePublished();
        boolean existEventsInDatabaseDeadLetter    = existEventsInDatabaseDeadLetter();

        if (existEventsInDatabaseDeadLetter && rabbitMQIsRunning) {
            log.info("...There are events in the database 'dead letter' --> please, check them, they will no be retried to publish.");
        }

        if (!existEventsInDatabaseToBePublished) {
            log.info("...There are not events in database awaiting to be published");

        } else {

            if (rabbitMQIsRunning) {
                log.info("...There are events in database awaiting to be published --> trying to publish them...");
                postgresDomainEventsConsumer.consume();

            } else {
                log.error("...There are events in database awaiting to be published, but RabbitMQ is not running: please check RabbitMQ status");
            }
        }

        statusChecked();

    }

    private boolean rabbitMQIsRunning() {
        return rabbitMqPublisher.checkRabbitMQIsRunning();
    }

    private boolean existEventsInDatabaseToBePublished() {
        log.info("Checking if there are domain events stored in the database to be published...");
        return postgresDomainEventsConsumer.existEventsInDatabaseRetryQueue();
    }

    private boolean existEventsInDatabaseDeadLetter() {
        return postgresDomainEventsConsumer.existEventsInDatabaseDeadLetterQueue();
    }

    private boolean statusHasBeenCheckedBefore() {
        return supervisedBefore;
    }

    private void statusChecked() {
        supervisedBefore = true;

    }

    private boolean connectionWasRunningBefore() {
        return connectionWasRunningBefore;
    }

    private void connectionRunning() {
        connectionWasRunningBefore = true;

    }

    public void stopEventDatabaseConsumer() {
        if (postgresDomainEventsConsumer.isRunning()) {
            log.error("Connection with message broker is lost --> stopping the event database consumer...");
            postgresDomainEventsConsumer.stop();
        }
    }
}

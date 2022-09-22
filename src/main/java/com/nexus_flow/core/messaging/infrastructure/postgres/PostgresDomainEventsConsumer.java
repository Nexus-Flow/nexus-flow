package com.nexus_flow.core.messaging.infrastructure.postgres;

import com.nexus_flow.core.ddd.exceptions.RetrievingEventFromDatabaseError;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.messaging.domain.DomainEventsConsumer;
import com.nexus_flow.core.messaging.infrastructure.rabbitmq.RabbitMqEventBus;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service(value = "database-consumer")
@Slf4j
public class PostgresDomainEventsConsumer implements DomainEventsConsumer {

    private final DomainEventMapper                 domainEventMapper;
    private final SpringDataJpaDomainEventInterface springDataJpaDomainEventInterface;
    private final RabbitMqEventBus rabbitMqEventBus;

    private final Integer CHUNKS                     = 100;
    private final Integer SHOULD_STOP_AT_TIMES_EMPTY = 2;
    private final Integer TIME_TO_SLEEP              = 1000;
    private final Integer MAX_ATTEMPTS_TO_PUBLISH    = 4;
    private       Boolean running                    = false;


    public PostgresDomainEventsConsumer(DomainEventMapper domainEventMapper,
                                        SpringDataJpaDomainEventInterface springDataJpaDomainEventInterface,
                                        RabbitMqEventBus rabbitMqEventBus) {
        this.domainEventMapper                 = domainEventMapper;
        this.springDataJpaDomainEventInterface = springDataJpaDomainEventInterface;
        this.rabbitMqEventBus                  = rabbitMqEventBus;
    }

    @Override
    public void consume() throws RetrievingEventFromDatabaseError {

        running = true;

        // Gets the different attempts numbers in the database and splits them into two lists:
        // The ones below the max attempts and the ones equals and above
        Map<Boolean, List<Integer>> differentAttemptsNumbersInDataBase = springDataJpaDomainEventInterface
                .findDistinctByOrderByAttemptsToPublishDesc()
                .stream()
                .map(DomainEventEntity::getAttemptsToPublish)
                .distinct()
                .collect(Collectors.partitioningBy(times -> times < MAX_ATTEMPTS_TO_PUBLISH));

        // Gets the list of the ones that can be retry to publish
        List<Integer> retryableAttemptsNumbers = differentAttemptsNumbersInDataBase.get(true);

        if (retryableAttemptsNumbers.isEmpty()) {
            log.info("...There are no events stored in the database.");
            stop();
        }


        for (int attemptNumber : retryableAttemptsNumbers) {
            if (isRunning()) {
                try {
                    processEventsWithAttemptNumber(attemptNumber);
                } catch (Exception e) {
                    stop();
                    throw new RetrievingEventFromDatabaseError(e);
                }
            }
        }

        stop();

        log.info("Database events consumer has stopped. Checking status...");

        checkCurrentDatabaseStatus();

    }

    @Override
    public void stop() {
        running = false;
    }

    public void checkCurrentDatabaseStatus() {
        Long retryQueue      = springDataJpaDomainEventInterface.countByAttemptsToPublishLessThan(MAX_ATTEMPTS_TO_PUBLISH);
        Long deadLetterQueue = springDataJpaDomainEventInterface.countByAttemptsToPublishGreaterThanEqual(MAX_ATTEMPTS_TO_PUBLISH);
        if (retryQueue > 0) {
            log.warn("...There are still {} events that could not be published to the message broker. " +
                            "If the connection is lost there will be a retry when it comes back.",
                    retryQueue);
        }
        if (deadLetterQueue > 0) {
            log.warn("...There are still {} events in the 'dead letter' of the database. " +
                            "Please check what's wrong with them.",
                    deadLetterQueue);
        }
    }

    /**
     * Fetches the next chunk of events that has been tried to publish n times
     * If the events have been tried to publish only once, means there are the last to be stored
     * In this case, when the chunk list is empty, checks if there are events stored while
     * consumer was running, in order to ensure no event is left to be tried to publish
     *
     * @param attemptNumber times the event was tried to be published
     */
    private void processEventsWithAttemptNumber(int attemptNumber) throws InterruptedException {

        int               timesChunkListEmpty = 0;
        List<DomainEvent> chunkOfStoredEvents;

        do {
            if (!isRunning()) {
                return;
            }

            chunkOfStoredEvents = nextDatabaseChunk(attemptNumber);

            if (!chunkOfStoredEvents.isEmpty()) {
                processChunkOfEvents(new ArrayList<>(chunkOfStoredEvents), attemptNumber);
            }

            // The attemptNumber 1 is special: is the last to be processed, so -->
            // Given the fact that chunkOfStoredEvents is EMPTY, checks if should continue running,
            // i.e. all events of database have been republish to message broker
            if (chunkOfStoredEvents.isEmpty() && attemptNumber == 1) {

                timesChunkListEmpty++;

                if (timesChunkListEmpty == 1) {
                    log.info("All events in database where processed. " +
                            "--> waiting to check if there where some insertions while running consumer...");
                }
                // Sleeps in order to check again the database,
                // ensuring no event has been stored in the database recently,
                // while this consumer has been running
                if (timesChunkListEmpty < SHOULD_STOP_AT_TIMES_EMPTY) {
                    Thread.sleep(TIME_TO_SLEEP);
                }

            }

        } while (attemptNumber == 1 ?
                timesChunkListEmpty < MAX_ATTEMPTS_TO_PUBLISH :
                !chunkOfStoredEvents.isEmpty());

        if (attemptNumber == 1) {
            log.info("...No events where stored in database while running consumer.");
        }

    }


    @Transactional
    void processChunkOfEvents(List<DomainEvent> storedEvents, int attemptNumber){

        rabbitMqEventBus.publish(storedEvents);

        // Removes the ones that have given another error (its attempt number has augmented)
        // Because they have been stored again with the new attempt number
        storedEvents.removeIf(domainEvent -> domainEvent.getTimesWasTriedToPublish() > attemptNumber);

        springDataJpaDomainEventInterface.deleteAll(domainEventMapper.toEntity(storedEvents));

    }


    public List<DomainEvent> nextDatabaseChunk(Integer attemptNumber)  {
        return domainEventMapper.toDomain(springDataJpaDomainEventInterface
                .findAllByAttemptsToPublishOrderByOccurredOn(attemptNumber,
                        PageRequest.of(0, CHUNKS, Sort.by(Sort.Direction.ASC, "occurredOn"))));
    }

    public boolean existEventsInDatabaseRetryQueue() {
        return springDataJpaDomainEventInterface
                .findTopByAttemptsToPublishLessThanOrderByOccurredOn(MAX_ATTEMPTS_TO_PUBLISH)
                .isPresent();
    }

    public boolean existEventsInDatabaseDeadLetterQueue() {
        return springDataJpaDomainEventInterface
                .findTopByAttemptsToPublishGreaterThanEqualOrderByOccurredOn(MAX_ATTEMPTS_TO_PUBLISH)
                .isPresent();
    }


    public boolean isRunning() {
        return running;
    }
}


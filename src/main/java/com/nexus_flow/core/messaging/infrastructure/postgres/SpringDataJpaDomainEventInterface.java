package com.nexus_flow.core.messaging.infrastructure.postgres;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;


public interface SpringDataJpaDomainEventInterface extends Repository<DomainEventEntity, String> {

    void save(DomainEventEntity domainEvent);

    void saveAll(Iterable<DomainEventEntity> domainEvents);

    List<DomainEventEntity> findAllByAttemptsToPublishOrderByOccurredOn(Integer times, Pageable pageable);

    void deleteAll(Iterable<DomainEventEntity> domainEvents);


    Long countByAttemptsToPublishLessThan(int times);

    Long countByAttemptsToPublishGreaterThanEqual(int times);

    Optional<DomainEventEntity> findTopByAttemptsToPublishLessThanOrderByOccurredOn(int times);

    Optional<DomainEventEntity> findTopByAttemptsToPublishGreaterThanEqualOrderByOccurredOn(int times);


    List<DomainEventEntity> findDistinctByOrderByAttemptsToPublishDesc();

    void flush();
}


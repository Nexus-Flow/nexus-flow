package org.nexusflow.core.ddd;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

sealed interface AggregateRoot extends Serializable permits Aggregate {

    default UUID getAggregateId() {
        return UUID.randomUUID();
    }

    default Instant getTimestamp() {
        return Instant.now();
    }

    void recordEvent(List<DomainEvent> domainEvents);

    void recordEvent(DomainEvent domainEvent);

    List<DomainEvent> getEvents();

}
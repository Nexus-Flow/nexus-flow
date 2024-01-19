package org.nexusflow.core.ddd;

import java.time.Instant;
import java.util.UUID;

public non-sealed abstract class AbstractDomainEvent implements DomainEvent {

    private final UUID id;
    private final Instant timestamp;
    private final String aggregateId;

    protected AbstractDomainEvent(String aggregateId) {
        this.id = UUID.randomUUID();
        this.timestamp = Instant.now();
        this.aggregateId = aggregateId;
    }

    @Override
    public UUID getId() {
        return this.id;
    }

    @Override
    public Instant getTimestamp() {
        return this.timestamp;
    }

    @Override
    public String getAggregateId() {
        return this.aggregateId;
    }

}
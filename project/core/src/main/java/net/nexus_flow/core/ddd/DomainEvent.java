package net.nexus_flow.core.ddd;

import java.time.Instant;
import java.util.UUID;

public sealed interface DomainEvent permits AbstractDomainEvent {

    UUID getId();

    Instant getTimestamp();

    String getAggregateId();

}
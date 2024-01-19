package org.nexusflow.core.cqrs.query;

import org.nexusflow.core.cqrs.reflection.TypeReference;

import java.time.Instant;
import java.util.UUID;

public sealed interface Query<T extends Record> permits DefaultQuery {

    // Static factory method to obtain a builder instance
    static <T extends Record> QueryBuilder.BodyStep<T> builder() {
        return QueryBuilder.builder();
    }

    default UUID getQueryId() {
        return UUID.randomUUID();
    }

    default Instant getTimestamp() {
        return Instant.now();
    }

    T getBody();

    TypeReference<T> getType();

}

package net.nexus_flow.core.cqrs.query;

import net.nexus_flow.core.cqrs.reflection.TypeReference;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

non-sealed class DefaultQuery<T extends Record> implements Query<T> {
    private final UUID queryId;
    private final Instant timestamp;
    private final T body;
    private final TypeReference<T> type;

    protected DefaultQuery(QueryBuilder<T> builder) {
        this.queryId = builder.getQueryId() != null ? builder.getQueryId() : UUID.randomUUID();
        this.timestamp = builder.getTimestamp() != null ? builder.getTimestamp() : Instant.now();
        this.body = builder.getBody();
        this.type = builder.getType();
    }

    @Override
    public UUID getQueryId() {
        return queryId;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public T getBody() {
        return body;
    }

    @Override
    public TypeReference<T> getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DefaultQuery<?> that)) return false;
        return Objects.equals(getQueryId(), that.getQueryId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getQueryId());
    }

}

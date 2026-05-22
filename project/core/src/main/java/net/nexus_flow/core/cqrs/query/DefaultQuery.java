package net.nexus_flow.core.cqrs.query;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.runtime.ids.FastUuid;
import net.nexus_flow.core.types.TypeReference;

final class DefaultQuery<T extends Record> implements Query<T> {
    private final UUID             queryId;
    private final Instant          timestamp;
    private final T                body;
    private final TypeReference<T> type;

    /**
     * Creates a query from the staged builder state.
     *
     * @param builder staged builder supplying the query metadata and body
     */
    protected DefaultQuery(QueryBuilder<T> builder) {
        this.queryId   = builder.getQueryId() != null ? builder.getQueryId() : FastUuid.v4();
        this.timestamp = builder.getTimestamp() != null ? builder.getTimestamp() : Instant.now();
        this.body      = builder.getBody();
        this.type      = builder.getType();
    }

    /**
     * Returns the stable identifier of this query.
     *
     * @return query identifier
     */
    @Override
    public UUID getQueryId() {
        return queryId;
    }

    /**
     * Returns the query creation timestamp.
     *
     * @return creation instant
     */
    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the immutable query payload.
     *
     * @return query body
     */
    @Override
    public T getBody() {
        return body;
    }

    /**
     * Returns the query body type token.
     *
     * @return query body type token
     */
    @Override
    public TypeReference<T> getType() {
        return type;
    }

    /**
     * Compares query envelopes by identifier.
     *
     * @param o other object
     * @return {@code true} when both queries carry the same identifier
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof DefaultQuery<?> that))
            return false;
        return Objects.equals(getQueryId(), that.getQueryId());
    }

    /**
     * Returns the identifier-based hash code.
     *
     * @return hash code derived from {@link #getQueryId()}
     */
    @Override
    public int hashCode() {
        return Objects.hash(getQueryId());
    }
}

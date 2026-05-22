package net.nexus_flow.core.cqrs.query;

import java.time.Instant;
import java.util.UUID;
import net.nexus_flow.core.types.TypeReference;

/**
 * Immutable query envelope dispatched through a {@link QueryBus}.
 *
 * <p>The envelope carries an identifier, a creation timestamp, and the query body record used as
 * the routing key. Query routing is based on the exact runtime class of {@link #getBody()}.
 *
 * @param <T> query body type
 */
public sealed interface Query<T extends Record> permits DefaultQuery {

    /**
     * Creates a staged builder for a query envelope.
     *
     * @param <T> query body type
     * @return builder whose first step captures the query body instance
     */
    static <T extends Record> QueryBuilder.BodyStep<T> builder() {
        return QueryBuilder.builder();
    }

    /**
     * Returns the stable identifier of this query dispatch.
     *
     * @return query identifier
     */
    default UUID getQueryId() {
        return UUID.randomUUID();
    }

    /**
     * Returns the creation timestamp of this query envelope.
     *
     * @return creation instant
     */
    default Instant getTimestamp() {
        return Instant.now();
    }

    /**
     * Returns the query payload dispatched to the handler.
     *
     * @return immutable query body
     */
    T getBody();

    /**
     * Returns the type token representing {@link #getBody()}.
     *
     * @return query body type token
     */
    TypeReference<T> getType();
}

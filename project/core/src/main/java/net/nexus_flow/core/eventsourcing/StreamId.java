package net.nexus_flow.core.eventsourcing;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a single event stream inside an {@link EventStore}.
 *
 * <p>An event stream is the canonical log of every event ever produced by a single aggregate
 * instance, identified by the pair {@code (aggregateType, aggregateId)}. The {@code aggregateType}
 * carries the fully-qualified class name of the aggregate (mirror of the {@code aggregateType}
 * column in {@link net.nexus_flow.core.outbox.OutboxRecord#aggregateType()}); the {@code
 * aggregateId} is the canonical id stamped on every {@link
 * net.nexus_flow.core.ddd.AbstractDomainEvent}.
 *
 * @param aggregateType fully-qualified type name; non-blank
 * @param aggregateId   business identity of the aggregate instance
 */
public record StreamId(String aggregateType, UUID aggregateId) {

    /**
     * Create a stream id.
     *
     * @param aggregateType fully-qualified aggregate type name
     * @param aggregateId   business identity of the aggregate instance
     * @throws NullPointerException     if {@code aggregateType} or {@code aggregateId} is {@code null}
     * @throws IllegalArgumentException if {@code aggregateType} is blank
     */
    public StreamId {
        Objects.requireNonNull(aggregateType, "aggregateType");
        if (aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType must not be blank");
        }
        Objects.requireNonNull(aggregateId, "aggregateId");
    }

    /**
     * Convenience factory taking an aggregate {@link Class}.
     *
     * @param aggregateType the aggregate class; non-null
     * @param aggregateId   business identity of the aggregate instance; non-null
     * @return a {@code StreamId} whose {@code aggregateType} is the class's binary name
     */
    public static StreamId of(Class<?> aggregateType, UUID aggregateId) {
        Objects.requireNonNull(aggregateType, "aggregateType");
        return new StreamId(aggregateType.getName(), aggregateId);
    }
}

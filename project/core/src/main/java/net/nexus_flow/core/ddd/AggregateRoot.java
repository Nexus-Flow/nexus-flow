package net.nexus_flow.core.ddd;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sealed interface defining the contract for aggregate roots in the DDD model.
 *
 * <p>An aggregate root is the entry point to an aggregate — a cluster of domain objects bound
 * together to act as a single unit for change purposes. This interface defines the core methods for
 * recording domain events, managing versions, and querying event history.
 *
 * <p>Implementations are responsible for maintaining the sequence of events that led to the
 * aggregate's current state, enabling event sourcing and optimistic concurrency control.
 *
 * @see Aggregate for the concrete implementation
 * @see DomainEvent for the event contract
 */
sealed interface AggregateRoot extends Serializable permits Aggregate {

    /**
     * Returns this aggregate root's unique identifier.
     *
     * <p>The default implementation generates a random UUID; subclasses should override to return the
     * actual aggregate ID.
     *
     * <p><strong>Why {@link UUID#randomUUID()} and not the framework's faster {@code FastUuid.v4()}
     * helper.</strong> Aggregate ids are the canonical privacy-boundary identifier — they regularly
     * surface in external URLs and API responses (e.g. {@code /orders/{aggregateId}}). A predictable
     * id generator (the framework's {@code FastUuid} uses {@link
     * java.util.concurrent.ThreadLocalRandom}) would let attackers enumerate other users' resources.
     * Aggregate identity allocation is NOT a hot path — the performance win the framework chases on
     * the dispatch envelope ({@code MessageId}, {@code TraceId}, ...) does not apply here, so the
     * SecureRandom-backed default stays.
     *
     * @return the aggregate's unique identifier
     */
    default UUID getAggregateId() {
        return UUID.randomUUID();
    }

    /**
     * Returns the timestamp when this aggregate root was last modified.
     *
     * <p>The default implementation returns the current instant; subclasses should override to return
     * the actual modification timestamp.
     *
     * @return the modification timestamp
     */
    default Instant getTimestamp() {
        return Instant.now();
    }

    /**
     * Records a batch of domain events on this aggregate.
     *
     * <p>Each event is applied to the aggregate state and added to the uncommitted buffer. Events are
     * stamped with a per-aggregate-instance sequence number and made observable to registered
     * listeners.
     *
     * @param domainEvents a list of domain events to record; never {@code null}
     * @see #recordEvent(DomainEvent)
     */
    void recordEvent(List<DomainEvent> domainEvents);

    /**
     * Records a single domain event on this aggregate.
     *
     * <p>The event is applied to the aggregate state, stamped with a per-aggregate-instance sequence
     * number, and added to the uncommitted buffer. After recording, the event becomes observable to
     * registered listeners.
     *
     * @param domainEvent the domain event to record; never {@code null}
     * @see #recordEvent(List)
     */
    void recordEvent(DomainEvent domainEvent);

    /**
     * Returns a copy of all events recorded on this aggregate since the last clear or drain
     * operation.
     *
     * @return an immutable list of recorded events; never {@code null}
     */
    List<DomainEvent> getEvents();

    /**
     * Returns the committed stream version of this aggregate.
     *
     * <p>The version represents the {@code streamVersion} of the last envelope flushed to the event
     * store. Newly constructed aggregates start at {@code 0}; freshly loaded aggregates carry the
     * last known version.
     *
     * <p>{@link #recordEvent(DomainEvent)} does NOT bump this counter — uncommitted events sit in the
     * buffer until {@link #markCommitted(long)} fires.
     *
     * @return the committed version; always &gt;= 0
     */
    long version();

    /**
     * Marks the current batch of uncommitted events as committed.
     *
     * <p>This callback is fired by the aggregate repository after a successful event store append. It
     * clears the uncommitted event buffer and updates the committed version for the next optimistic
     * concurrency check.
     *
     * @param newVersion the new committed stream version; must equal {@code currentVersion +
     *     uncommittedEventCount}
     * @throws IllegalArgumentException if the new version does not match the expected value
     */
    void markCommitted(long newVersion);
}

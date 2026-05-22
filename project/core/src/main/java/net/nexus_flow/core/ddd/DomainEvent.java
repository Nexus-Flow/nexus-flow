package net.nexus_flow.core.ddd;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sealed interface defining the contract for domain events in the DDD/Event Sourcing model.
 *
 * <p>A domain event is an immutable record of something that happened in the domain. Events
 * represent facts about the past and are the primary mechanism for communicating state changes
 * between aggregates. Events are never removed or modified — only new events can be recorded.
 *
 * <p>Implementations must provide a stable {@code eventType()} identifier for wire-format
 * serialization and a deduplication key via {@code idempotencyKey()} for idempotent delivery.
 *
 * @see AbstractDomainEvent for the standard implementation
 * @see AggregateRoot#recordEvent(DomainEvent)
 */
public sealed interface DomainEvent permits AbstractDomainEvent {

    /**
     * Returns the unique identifier of this domain event instance.
     *
     * @return the event's UUID; never {@code null}
     */
    UUID getId();

    /**
     * Returns the instant when this domain event was created.
     *
     * @return the event's timestamp; never {@code null}
     */
    Instant getTimestamp();

    /**
     * Returns the unique identifier of the aggregate that produced this event.
     *
     * @return the aggregate ID; never {@code null}
     */
    String getAggregateId();

    /**
     * Returns the event's metadata headers (e.g. correlation-id, trace-id).
     *
     * <p>Default implementation returns an empty map.
     *
     * @return an immutable map of metadata headers; never {@code null}, may be empty
     */
    default Map<String, String> getHeaders() {
        return java.util.Collections.emptyMap();
    }

    /**
     * Returns the stable string identifier for this event type.
     *
     * <p>Used for routing, schema registries, outbox serialization, and consumer subscription
     * filters. The default implementation returns {@code getClass().getSimpleName()}, which is
     * adequate for in-process dispatch. Override for cross-system communication to decouple the wire
     * name from the Java class name.
     *
     * <p>Example override for versioned event types:
     *
     * <p>
     *
     * {@snippet :
     * // Override the event type for versioned wire formats
     * &#64;Override
     * public String eventType() {
     *     return "order.placed.v2";
     * }
     * }
     *
     * @return the stable event type identifier; never {@code null} or blank
     */
    default String eventType() {
        return getClass().getSimpleName();
    }

    /**
     * Returns the canonical deduplication handle for downstream listeners.
     *
     * <p>Most notably used by the outbox for exactly-once-effective delivery. The default
     * implementation throws {@link UnsupportedOperationException}; {@link AbstractDomainEvent}
     * overrides it to return {@code aggregateId + ":" + sequenceNumber} once the event has been
     * recorded on an {@link AggregateRoot}. Custom {@link DomainEvent} subtypes that carry their own
     * exactly-once-effective handle (e.g. events imported from an external broker) MUST override this
     * method.
     *
     * <p>Two publications of the same event (online dispatch + an outbox retry) MUST return the same
     * {@code idempotencyKey()}; a listener that needs exactly-once-effective semantics can therefore
     * deduplicate by this string alone.
     *
     * @return a stable, non-empty deduplication key for this event
     * @throws UnsupportedOperationException when the event lacks a derivable idempotency key (e.g. an
     *                                       {@link AbstractDomainEvent} that was instantiated outside of {@code
     *     AggregateRoot.recordEvent}     and not overridden)
     */
    default String idempotencyKey() {
        throw new UnsupportedOperationException("event lacks idempotencyKey");
    }
}

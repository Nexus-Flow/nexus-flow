package net.nexus_flow.core.outbox;

import java.util.Objects;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * type-safe wrapper around the deduplication handle produced by {@link
 * DomainEvent#idempotencyKey()} ().
 *
 * <p>The wrapped {@code String} is treated as opaque by every subsystem below the recordEvent
 * boundary: storage backends compare it as-is for dedup, the worker uses it verbatim as the
 * cross-restart fingerprint. The canonical shape for events recorded through {@code
 * AggregateRoot.recordEvent(...)} is {@code aggregateId + ":" + sequenceNumber}; external events
 * may carry an upstream handle (e.g. a Kafka offset, a webhook payload hash) instead.
 */
public record IdempotencyKey(String value) implements Comparable<IdempotencyKey> {

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey value must not be blank");
        }
    }

    /** Adapter sugar: lifts a raw string into an {@code IdempotencyKey}. */
    public static IdempotencyKey of(String raw) {
        return new IdempotencyKey(raw);
    }

    /**
     * Adapter sugar: invokes {@link DomainEvent#idempotencyKey()} and wraps the result.
     *
     * @throws UnsupportedOperationException propagated from the event when it lacks a derivable key.
     */
    public static IdempotencyKey from(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        return new IdempotencyKey(event.idempotencyKey());
    }

    @Override
    public int compareTo(IdempotencyKey o) {
        return this.value.compareTo(o.value);
    }

    @Override
    public String toString() {
        return value;
    }
}

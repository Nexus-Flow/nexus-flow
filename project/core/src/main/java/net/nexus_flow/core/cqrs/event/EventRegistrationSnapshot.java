package net.nexus_flow.core.cqrs.event;

import java.util.Map;
import java.util.Set;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * read-only diagnostic view of the per-runtime event-listener registry.
 *
 * <p>Mirrors the shape of {@link net.nexus_flow.core.cqrs.command.CommandRegistrationSnapshot
 * CommandRegistrationSnapshot} so observability / debug tooling can introspect commands and events
 * with an identical access pattern.
 *
 * <p>The snapshot is a value object — mutating the bus after the snapshot is taken does not
 * retroactively change the captured data.
 *
 * @param eventTypes immutable map from concrete {@link DomainEvent} subclass to the number of
 *                   listeners registered for that type. A type with zero listeners never appears in the map.
 */
public record EventRegistrationSnapshot(Map<Class<? extends DomainEvent>, Integer> eventTypes) {

    /**
     * Normalizes the captured snapshot to an immutable map.
     *
     * @param eventTypes the event-type registration counts captured at snapshot time
     */
    public EventRegistrationSnapshot {
        eventTypes = Map.copyOf(eventTypes);
    }

    /** Returns the set of event classes that have at least one listener registered. */
    public Set<Class<? extends DomainEvent>> registeredEventClasses() {
        return eventTypes.keySet();
    }

    /** Total number of individual listener registrations across all event types. */
    public int totalListenerCount() {
        int sum = 0;
        for (int v : eventTypes.values()) {
            sum += v;
        }
        return sum;
    }

    /** Number of distinct event types that have at least one listener. */
    public int size() {
        return eventTypes.size();
    }

    /** {@code true} iff no listeners are registered. */
    public boolean isEmpty() {
        return eventTypes.isEmpty();
    }
}

package net.nexus_flow.core.cqrs.event;

import java.util.Collections;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Snapshot of per-listener runtime statistics for the {@link EventBus}. Obtain via {@link
 * EventBus#stats()}.
 *
 * @param byListener immutable snapshot keyed by listener concrete class; multiple registrations of
 *                   the same class are aggregated into a single entry
 */
public record EventBusStats(Map<Class<?>, ListenerStats> byListener) {

    public static final EventBusStats EMPTY = new EventBusStats(Collections.emptyMap());

    /**
     * Normalizes the snapshot to an unmodifiable map.
     *
     * @param byListener the listener statistics captured at snapshot time
     */
    public EventBusStats {
        byListener = Collections.unmodifiableMap(byListener);
    }

    /** Returns the stats for the given listener class, or {@code null} if not found. */
    public @Nullable ListenerStats forListener(Class<?> listenerClass) {
        return byListener.get(listenerClass);
    }
}

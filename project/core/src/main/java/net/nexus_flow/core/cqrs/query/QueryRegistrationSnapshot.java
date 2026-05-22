package net.nexus_flow.core.cqrs.query;

import java.util.Set;

/**
 * Read-only diagnostic view of the per-runtime query-handler registry.
 *
 * <p>Mirrors the shape of {@link net.nexus_flow.core.cqrs.command.CommandRegistrationSnapshot
 * CommandRegistrationSnapshot} and {@link net.nexus_flow.core.cqrs.event.EventRegistrationSnapshot
 * EventRegistrationSnapshot} so observability and debug tooling can introspect commands, events,
 * and queries with an identical access pattern.
 *
 * <p>Query handlers are 1:1 per exact query body type, so this snapshot carries only a flat {@code
 * Set} of registered query types.
 *
 * <p>The snapshot is a value object; mutating the bus after the snapshot is taken does not
 * retroactively change the captured set.
 *
 * @param queryTypes immutable set of exact query body record classes that have a handler registered
 */
public record QueryRegistrationSnapshot(Set<Class<?>> queryTypes) {

    /**
     * Creates an immutable snapshot.
     *
     * @throws NullPointerException if {@code queryTypes} is {@code null}
     */
    public QueryRegistrationSnapshot {
        queryTypes = Set.copyOf(queryTypes);
    }

    /**
     * Returns the number of registered query types.
     *
     * @return distinct registered query-type count
     */
    public int size() {
        return queryTypes.size();
    }

    /**
     * Returns whether the snapshot contains no registrations.
     *
     * @return {@code true} when no query handler is registered
     */
    public boolean isEmpty() {
        return queryTypes.isEmpty();
    }
}

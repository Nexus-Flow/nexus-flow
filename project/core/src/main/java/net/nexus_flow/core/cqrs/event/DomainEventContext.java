package net.nexus_flow.core.cqrs.event;

import java.util.List;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * Sealed contract for the per-handler domain-event sink.
 *
 * <p>The interface is {@code sealed} so that any switch over {@code DomainEventContext} variants in
 * the runtime can be exhaustive without a {@code default} branch — adding a new implementation must
 * surface as a compile-time obligation.
 *
 * <p>the JVM-wide accessor — previously exposed by the deprecated {@code DomainEventContextHolder}
 * shim — lives here now. The {@link #current()} method returns the singleton selected by the {@code
 * eventContextStrategy} system property at class-load time:
 *
 * <ul>
 * <li>{@code "scoped"} (default) → {@link ScopedDomainEventContext}
 * <li>{@code "thread-local"} → {@link ThreadLocalDomainEventContext}
 * </ul>
 *
 * Both implementations are {@code permits}-bound to this interface; new variants must be added to
 * the permits clause so the runtime's exhaustive switches keep compiling.
 */
public sealed interface DomainEventContext
        permits ScopedDomainEventContext, ThreadLocalDomainEventContext {

    /**
     * Process-wide singleton selected by the {@code eventContextStrategy} system property. Strategy
     * choice is one-shot at class-init.
     */
    DomainEventContext INSTANCE =
            "thread-local".equalsIgnoreCase(System.getProperty("eventContextStrategy",
                                                               "scoped")) ? new ThreadLocalDomainEventContext() : new ScopedDomainEventContext();

    /** Returns the JVM-wide {@link DomainEventContext} singleton. */
    // Singleton by design; always returns the same INSTANCE.
    //noinspection SameReturnValue
    static DomainEventContext current() {
        return INSTANCE;
    }

    /**
     * Records a freshly emitted domain event in the active sink for the current execution.
     *
     * @param event the event to buffer until the surrounding command or handler drains it
     */
    void recordEvent(DomainEvent event);

    /**
     * Returns the live event buffer associated with the current execution.
     *
     * @return the mutable list that currently stores buffered events for this context
     */
    List<DomainEvent> getEvents();

    /**
     * Clears the active event buffer and releases any per-execution bookkeeping held by the
     * implementation.
     */
    void clearEvents();

    /**
     * Reports whether the current execution has recorded at least one event since the last reset or
     * clear.
     *
     * @return {@code true} when the active execution has recorded events, otherwise {@code false}
     */
    boolean hasEventsRecorded();

    /**
     * Resets the "events recorded" marker for the current execution without changing strategy-wide
     * configuration.
     */
    void resetEventsRecorded();
}

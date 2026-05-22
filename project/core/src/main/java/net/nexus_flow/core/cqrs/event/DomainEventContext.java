package net.nexus_flow.core.cqrs.event;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
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
     * Surgical removal: takes the specified event references out of the active sink while
     * leaving every other recorded event untouched. Designed for the aggregate-scoped path
     * where a handler touches several aggregates in the same scope and persists one of them
     * via the repository — the saved aggregate's events should leave the ambient sink (so the
     * runtime's drain does not re-publish them) while the OTHER aggregates' events stay.
     *
     * <p>Removal uses identity equality on the event references (the same instances are
     * shared between the aggregate-local buffer and the ambient sink, since both are
     * appended by the same {@code Aggregate.recordEvent} call). Non-matching entries are
     * silently ignored — the operation is idempotent on a sink that no longer contains the
     * requested events.
     *
     * <p>If the sink becomes empty as a result, {@link #hasEventsRecorded()} STAYS {@code
     * true} until {@link #resetEventsRecorded()} or {@link #clearEvents()} is called — the
     * flag captures "events were recorded during this execution", not "events are still
     * present". This matches the semantics that the runtime's drain loop expects.
     *
     * @param events events to remove from the active sink; never {@code null}; empty is a no-op
     */
    void removeRecordedEvents(java.util.List<DomainEvent> events);

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

    /**
     * Run {@code body} with a freshly-bound event sink that lives for the dynamic extent of the
     * call, then hand the drained events to {@code drainCallback}. Returns the value produced by
     * {@code body}.
     *
     * <p>This is the abstraction the runtime (and adapter code) uses to capture events emitted
     * <em>anywhere</em> inside {@code body}, including events emitted from inside parallel
     * listener {@code VirtualThread} subtasks that the body might fork.
     *
     * <p>Implementations:
     *
     * <ul>
     * <li>{@link ScopedDomainEventContext} binds via {@link ScopedValue#where} so every child
     * {@code StructuredTaskScope} fork sees the same {@code Sink} reference — emissions
     * from parallel listeners reach the same buffer.
     * <li>{@link ThreadLocalDomainEventContext} swaps the calling thread's local buffer for a
     * fresh one and restores it on exit. Cross-thread propagation is intentionally not
     * supported in this backend — callers that fork into other threads or VTs must use
     * {@code ScopedDomainEventContext} (or accept that fork-emitted events will not reach
     * the drain).
     * </ul>
     *
     * <p>The {@code drainCallback} receives an unmodifiable snapshot of the events recorded
     * during {@code body} (empty list if none). Implementations call it AFTER {@code body}
     * returns successfully; on exception thrown by {@code body} the callback is NOT invoked
     * and the exception is rethrown.
     *
     * @param body          work to execute inside the bound sink scope; must not be {@code null}
     * @param drainCallback handed the drained events on successful return; must not be
     *                      {@code null}
     * @param <T>           value produced by {@code body}
     * @return value produced by {@code body}
     * @throws Exception any checked exception thrown by {@code body}
     */
    <T> T runWithFreshSink(Callable<T> body, Consumer<List<DomainEvent>> drainCallback) throws Exception;
}

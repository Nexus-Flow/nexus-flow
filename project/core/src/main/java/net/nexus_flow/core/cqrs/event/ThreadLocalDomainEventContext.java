package net.nexus_flow.core.cqrs.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * {@link DomainEventContext} backed by {@link ThreadLocal} state.
 *
 * <p>Each thread sees an independent mutable event buffer and an independent recorded-state flag.
 * Calling {@link #recordEvent(DomainEvent)} appends to the current thread's buffer; no events are
 * visible across threads even when the same context instance is shared process-wide.
 *
 * <p>{@link #clearEvents()} acts as the lifecycle "pop": it removes both the current thread's
 * buffer and its recorded-state marker so pooled or virtual threads start the next logical unit of
 * work from a clean slate. {@link #resetEventsRecorded()} only resets the flag for the current
 * thread and intentionally leaves the buffered events untouched.
 *
 * <p><strong>Concurrency semantics:</strong> the class is safe to share because all mutable state
 * is thread-confined. Coordination across threads is intentionally unsupported; callers that need
 * lexical propagation into child tasks should prefer {@link ScopedDomainEventContext}.
 */
public final class ThreadLocalDomainEventContext implements DomainEventContext {
    private static final ThreadLocal<List<DomainEvent>> DOMAIN_EVENTS   =
            ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Boolean>           EVENTS_RECORDED =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Appends the event to the current thread's buffer and marks this thread as having recorded
     * events.
     *
     * @param event the event emitted on the current thread
     */
    @Override
    public void recordEvent(DomainEvent event) {
        DOMAIN_EVENTS.get().add(Objects.requireNonNull(event, "event"));
        EVENTS_RECORDED.set(Boolean.TRUE);
    }

    /**
     * Returns the live buffer owned by the current thread.
     *
     * @return the current thread's mutable event buffer
     */
    @Override
    public List<DomainEvent> getEvents() {
        return DOMAIN_EVENTS.get();
    }

    /**
     * Removes the current thread's buffer and recorded-state marker.
     *
     * <p>The next access on the same thread receives a fresh empty buffer.
     */
    @Override
    public void clearEvents() {
        DOMAIN_EVENTS.remove();
        EVENTS_RECORDED.remove();
    }

    /**
     * Returns whether the current thread has recorded at least one event since the last reset or
     * clear.
     *
     * @return {@code true} if the current thread has recorded events
     */
    @Override
    public boolean hasEventsRecorded() {
        return EVENTS_RECORDED.get();
    }

    /** Resets the recorded-state flag for the current thread without clearing buffered events. */
    @Override
    public void resetEventsRecorded() {
        EVENTS_RECORDED.set(Boolean.FALSE);
    }
}

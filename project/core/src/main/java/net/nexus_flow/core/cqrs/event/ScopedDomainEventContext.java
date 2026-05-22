package net.nexus_flow.core.cqrs.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * {@link DomainEventContext} backed primarily by {@link ScopedValue}.
 *
 * <p>The runtime binds a fresh {@link Sink} with {@code ScopedValue.where(...).run/call(...)}
 * around a handler execution so child work created inside that lexical scope can resolve the same
 * context instance without relying on mutable static globals. When no caller-owned scoped binding
 * is active, this implementation falls back to a per-thread sink so direct usage from ordinary
 * threads still satisfies the contract.
 *
 * <p><strong>Concurrency semantics:</strong> the scoped binding propagates the <em>reference</em>
 * to the active sink, not a copy. The runtime therefore expects one logical command execution to
 * own the bound sink and drain it after work completes. The fallback path remains thread-confined,
 * matching {@link ThreadLocalDomainEventContext} semantics.
 *
 * <p><strong>Memory model:</strong> Both the event buffer and the "events were recorded" flag live
 * inside the {@link Sink} that the runtime binds. Because the binding has a strict lexical scope
 * ({@code ScopedValue.where(...).run(...)}), the Sink becomes unreachable as soon as the scope
 * unwinds — no static collection holds references on its behalf, so the context is leak-free even
 * if a caller forgets to call {@link #clearEvents()} explicitly.
 */
public final class ScopedDomainEventContext implements DomainEventContext {

    /**
     * Mutable execution-local pair of an event buffer and a "events were recorded" flag.
     *
     * <p>One {@code Sink} is created per handler invocation by {@code DefaultCommandHandlerExecutor}
     * and bound via {@link #getScopedValue()}. The runtime drains {@link #events()} when the handler
     * completes; the {@link #recorded()} flag is the write-observable contract for {@link
     * DomainEventContext#hasEventsRecorded()} and remains {@code true} even after the buffer is
     * drained by the runtime.
     */
    public static final class Sink {
        private final List<DomainEvent> events;
        private volatile boolean        recorded;

        /** Creates an empty {@code Sink} backed by a fresh {@link ArrayList}. */
        public Sink() {
            this(new ArrayList<>());
        }

        /**
         * Creates a {@code Sink} that wraps the supplied (mutable) event buffer.
         *
         * @param events the buffer to wrap; never {@code null}
         */
        public Sink(List<DomainEvent> events) {
            this.events = Objects.requireNonNull(events, "events");
        }

        /**
         * Returns the underlying event buffer. Callers may append, read or drain this list directly.
         *
         * @return the live, mutable event buffer; never {@code null}
         */
        public List<DomainEvent> events() {
            return events;
        }

        /**
         * Returns whether at least one event has been recorded into this sink during the current
         * execution.
         *
         * @return {@code true} once {@link #recordEvent} has appended at least one event
         */
        public boolean recorded() {
            return recorded;
        }

        void markRecorded() {
            this.recorded = true;
        }

        void resetRecorded() {
            this.recorded = false;
        }

        void clearAll() {
            events.clear();
            this.recorded = false;
        }
    }

    private static final ScopedValue<Sink> SINK     = ScopedValue.newInstance();
    private static final ThreadLocal<Sink> FALLBACK = ThreadLocal.withInitial(Sink::new);

    private static boolean scopedBindingActive() {
        return SINK.isBound();
    }

    private static Sink activeSink() {
        return scopedBindingActive() ? SINK.get() : FALLBACK.get();
    }

    /**
     * Appends the event to the currently bound scoped sink, or to the fallback thread-local sink when
     * no scoped binding is active.
     *
     * @param event the event emitted in the current execution
     */
    @Override
    public void recordEvent(DomainEvent event) {
        DomainEvent domainEvent = Objects.requireNonNull(event, "event");
        Sink        sink        = activeSink();
        if (scopedBindingActive()) {
            synchronized (sink) {
                sink.events.add(domainEvent);
                sink.markRecorded();
            }
            return;
        }
        sink.events.add(domainEvent);
        sink.markRecorded();
    }

    /**
     * Returns the live scoped sink when one is bound, otherwise the current thread's fallback sink.
     *
     * @return the mutable event buffer active for the current execution
     */
    @Override
    public List<DomainEvent> getEvents() {
        return activeSink().events;
    }

    /**
     * Clears the active sink and releases the recorded-state bookkeeping for the current execution.
     */
    @Override
    public void clearEvents() {
        Sink sink = activeSink();
        if (scopedBindingActive()) {
            synchronized (sink) {
                sink.clearAll();
            }
            return;
        }
        sink.clearAll();
        FALLBACK.remove();
    }

    /**
     * Returns whether the current scoped execution, or the current fallback thread, has recorded at
     * least one event.
     *
     * @return {@code true} when the active execution has recorded events
     */
    @Override
    public boolean hasEventsRecorded() {
        return activeSink().recorded();
    }

    /** Resets the recorded-state bookkeeping for the active scoped or fallback execution. */
    @Override
    public void resetEventsRecorded() {
        activeSink().resetRecorded();
    }

    /**
     * Exposes the scoped-value key that the command runtime binds with a fresh {@link Sink} per
     * handler invocation.
     *
     * @return the scoped-value key used to propagate the active event sink lexically
     */
    public ScopedValue<Sink> getScopedValue() {
        return SINK;
    }
}

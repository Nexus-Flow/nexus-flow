package net.nexus_flow.core.saga;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.DomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ExecutionContext;

/**
 * Reactive trigger that wakes a {@link SagaRunner#catchUp(ExecutionContext)} pass whenever a
 * domain event published through the runtime's {@link EventBus} matches the subscription.
 * Eliminates the "I forgot to schedule the catchUp" footgun — the runner becomes
 * event-driven without the caller having to wire a scheduled executor.
 *
 * <h2>Subscription scope</h2>
 *
 * <ul>
 * <li>{@link Builder#forEventTypes(Class[])} — recommended. Registers one listener per
 * specified event class, so the EventBus's per-type routing skips events the saga
 * does not care about. Use this when the saga reacts to a small, fixed set of event
 * types — the common case.
 * <li>{@link Builder#forAllDomainEvents()} — fallback. Subscribes to every domain event
 * published through the bus. Use only when the saga genuinely watches many types or
 * the type set is dynamic; the predicate filter narrows the wake-ups but the bus
 * routes every event to the trigger first.
 * </ul>
 *
 * <h2>Coalescing</h2>
 *
 * Multiple events arriving while a {@code catchUp} is already running coalesce into a
 * single follow-up — the runner runs at most twice in flight (one in-progress + one
 * pending) regardless of event arrival rate. Prevents per-event saga-runner thundering
 * herds.
 *
 * <h2>Threading</h2>
 *
 * The catchUp runs on a single-threaded executor owned by the trigger, NOT on the event
 * dispatcher thread. This keeps the EventBus dispatch path fast and lets the saga runner
 * absorb its own latency without blocking the event publisher.
 *
 * <h2>Cancellation</h2>
 *
 * Every catchUp invocation receives the {@link ExecutionContext} returned by the configured
 * {@link Builder#contextFactory(java.util.function.Supplier)} — by default a fresh root
 * context. Callers that want cooperative cancellation across catchUps should supply a
 * factory that returns the same long-lived context they hold elsewhere.
 */
public final class SagaEventBusTrigger implements AutoCloseable {

    private final SagaRunner                                       runner;
    private final EventBus                                         eventBus;
    private final java.util.function.Predicate<DomainEvent>        relevantEvent;
    private final java.util.function.Supplier<ExecutionContext>    contextFactory;
    private final Executor                                         executor;
    private final boolean                                          ownsExecutor;
    private final AtomicBoolean                                    catchUpInFlight = new AtomicBoolean();
    private final AtomicBoolean                                    coalescePending = new AtomicBoolean();
    private final AtomicBoolean                                    closed          = new AtomicBoolean();
    private final List<DomainEventListener<? extends DomainEvent>> listeners       = new ArrayList<>();

    private SagaEventBusTrigger(
            SagaRunner runner,
            EventBus eventBus,
            java.util.function.Predicate<DomainEvent> relevantEvent,
            java.util.function.Supplier<ExecutionContext> contextFactory,
            Executor executor,
            boolean ownsExecutor,
            List<Class<? extends DomainEvent>> eventTypes) {
        this.runner         = runner;
        this.eventBus       = eventBus;
        this.relevantEvent  = relevantEvent;
        this.contextFactory = contextFactory;
        this.executor       = executor;
        this.ownsExecutor   = ownsExecutor;
        if (eventTypes.isEmpty()) {
            // No specific types — broad subscription to every DomainEvent.
            DomainEventListener<DomainEvent> wideListener = new TriggerListener<>();
            eventBus.register(wideListener);
            listeners.add(wideListener);
        } else {
            for (Class<? extends DomainEvent> type : eventTypes) {
                registerForType(type);
            }
        }
    }

    private <E extends DomainEvent> void registerForType(Class<E> type) {
        DomainEventListener<E> listener = DomainEventListener.forEvent(type).handle(this::onEvent);
        eventBus.register(listener);
        listeners.add(listener);
    }

    private void onEvent(DomainEvent event) {
        if (closed.get()) {
            return;
        }
        if (!relevantEvent.test(event)) {
            return;
        }
        requestCatchUp();
    }

    /** Manually request a catchUp — useful for tests and for kicking the saga at startup. */
    public void requestCatchUp() {
        if (closed.get()) {
            return;
        }
        if (catchUpInFlight.compareAndSet(false, true)) {
            executor.execute(this::runCatchUp);
        } else {
            // catchUp already running — coalesce a follow-up so events that arrive during
            // the in-flight run still drive progress (the in-flight catchUp's tail may have
            // missed them).
            coalescePending.set(true);
        }
    }

    private void runCatchUp() {
        try {
            runner.catchUp(contextFactory.get());
        } catch (RuntimeException ignored) {
            // Saga-level failures are surfaced via SagaStorage + observability; the trigger
            // intentionally absorbs them so a single bad event does not poison the listener
            // subscription.
        } finally {
            catchUpInFlight.set(false);
            if (coalescePending.compareAndSet(true, false) && !closed.get()) {
                requestCatchUp();
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (DomainEventListener<? extends DomainEvent> l : listeners) {
                unregisterListener(l);
            }
            listeners.clear();
            if (ownsExecutor && executor instanceof java.util.concurrent.ExecutorService es) {
                es.shutdownNow();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends DomainEvent> void unregisterListener(DomainEventListener<E> listener) {
        eventBus.unregister(listener);
    }

    /**
     * Broad listener that captures {@code DomainEvent} as the type parameter via the
     * concrete-subclass token-resolution path. Used only when the builder is left without
     * an explicit {@code forEventTypes} set.
     */
    private final class TriggerListener<E extends DomainEvent> extends AbstractDomainEventListener<DomainEvent> {
        @Override
        public void handle(DomainEvent event) {
            onEvent(event);
        }
    }

    /** Builder for {@link SagaEventBusTrigger}. */
    public static Builder builder(SagaRunner runner, EventBus eventBus) {
        return new Builder(runner, eventBus);
    }

    /** Fluent builder. */
    public static final class Builder {
        private final SagaRunner                              runner;
        private final EventBus                                eventBus;
        private java.util.function.Predicate<DomainEvent>     relevantEvent  = e -> true;
        private java.util.function.Supplier<ExecutionContext> contextFactory = ExecutionContext::root;
        private Executor                                      executor;
        private final List<Class<? extends DomainEvent>>      eventTypes     = new ArrayList<>();
        private boolean                                       explicitForAll;

        private Builder(SagaRunner runner, EventBus eventBus) {
            this.runner   = Objects.requireNonNull(runner, "runner");
            this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        }

        /**
         * Restrict the trigger to a specific set of event types. Use this when the saga
         * reacts to a small, fixed set — the EventBus's per-type routing avoids invoking
         * the trigger for events the saga does not care about. Replaces any previous
         * {@code forEventTypes} call. Mutually exclusive with {@link #forAllDomainEvents()}.
         */
        @SafeVarargs
        public final Builder forEventTypes(Class<? extends DomainEvent>... types) {
            Objects.requireNonNull(types, "types");
            if (types.length == 0) {
                throw new IllegalArgumentException("forEventTypes requires at least one type");
            }
            if (explicitForAll) {
                throw new IllegalStateException(
                        "cannot call forEventTypes after forAllDomainEvents — they are exclusive");
            }
            eventTypes.clear();
            // Collections.addAll is @SafeVarargs (no heap-pollution warning) AND avoids the
            // intermediate ArrayList allocation {@code addAll(Arrays.asList(...))} would
            // create. PMD's UseArraysAsList rule is satisfied because the destination is a
            // pre-existing Collection.
            Collections.addAll(eventTypes, types);
            return this;
        }

        /**
         * Subscribe broadly to every domain event published through the bus — the trigger's
         * predicate filter is then the only gate. Use when the saga's relevant event set is
         * dynamic or large; otherwise prefer {@link #forEventTypes(Class[])} for
         * EventBus-level routing efficiency.
         */
        public Builder forAllDomainEvents() {
            if (!eventTypes.isEmpty()) {
                throw new IllegalStateException(
                        "cannot call forAllDomainEvents after forEventTypes — they are exclusive");
            }
            explicitForAll = true;
            return this;
        }

        /**
         * Filter the trigger to only fire on events matching the predicate. Default: every
         * event triggers a catchUp. Composes with {@link #forEventTypes(Class[])} —
         * routing narrows the types delivered to the trigger, the predicate then narrows
         * further on per-event content.
         */
        public Builder relevantEvent(java.util.function.Predicate<DomainEvent> predicate) {
            this.relevantEvent = Objects.requireNonNull(predicate, "predicate");
            return this;
        }

        /**
         * Override the {@link ExecutionContext} factory passed to {@link SagaRunner#catchUp}.
         * Defaults to {@link ExecutionContext#root()}. Production callers typically pass a
         * factory that returns the runtime's long-lived context so cancellation propagates
         * across runner invocations.
         */
        public Builder contextFactory(java.util.function.Supplier<ExecutionContext> factory) {
            this.contextFactory = Objects.requireNonNull(factory, "factory");
            return this;
        }

        /**
         * Override the executor that runs the catchUp pass. Defaults to a private
         * single-threaded executor owned by the trigger. When the caller supplies its own
         * executor, the trigger does NOT own it — close() will not shut it down.
         */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        public SagaEventBusTrigger build() {
            Executor effectiveExecutor;
            boolean  owns;
            if (executor == null) {
                effectiveExecutor = Executors.newSingleThreadExecutor(r -> {
                                      Thread t = new Thread(r, "nexus-saga-trigger");
                                      t.setDaemon(true);
                                      return t;
                                  });
                owns              = true;
            } else {
                effectiveExecutor = executor;
                owns              = false;
            }
            return new SagaEventBusTrigger(
                    runner, eventBus, relevantEvent, contextFactory,
                    effectiveExecutor, owns, eventTypes);
        }
    }
}

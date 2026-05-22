package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.PerRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;

/**
 * Per-runtime event bus.
 *
 * <p>Instances are owned by {@link net.nexus_flow.core.runtime.FlowRuntime}; obtain one via {@code
 * runtime.events()}. There is no process-wide accessor: see {@link PerRuntime} for the
 * architectural invariant pinned by {@code NoStaticGetInstanceTest}.
 */
@PerRuntime
public sealed interface EventBus permits DefaultEventBus {

    /**
     * build a fresh, runtime-scoped {@link EventBus}. Internal API: called from {@code
     * DefaultFlowRuntime}.
     *
     * <p>The bus owns a private {@code HandlerRegistry&lt;DomainEvent, Void&gt;} directly. Listener
     * storage is registry-backed rather than publisher-based, eliminating per-event-type virtual
     * thread executors. The registry is released on {@link #closeAll()}.
     */
    static EventBus newInstance() {
        return new DefaultEventBus();
    }

    /**
     * overload that enables opt-in parallel listener fan-out. Used by {@code DefaultFlowRuntime} when
     * the builder was configured via {@code FlowRuntime.Builder.parallelListeners(true)}.
     */
    static EventBus newInstance(boolean parallelListenersEnabled) {
        return new DefaultEventBus(parallelListenersEnabled);
    }

    /** Creates a bus with parallel listeners enabled and an initial DLQ. */
    static EventBus newInstance(boolean parallelListenersEnabled, DeadLetterQueue dlq) {
        DefaultEventBus bus = new DefaultEventBus(parallelListenersEnabled);
        if (dlq != null)
            bus.deadLetterQueue(dlq);
        return bus;
    }

    /**
     * full-fidelity factory with an injectable {@link java.time.Clock} so adapter modules and
     * deterministic tests can replace the wall clock used for dead-letter envelopes and any other
     * timestamp the bus needs to stamp.
     */
    static EventBus newInstance(boolean parallelListenersEnabled, java.time.Clock clock) {
        return new DefaultEventBus(
                parallelListenersEnabled, clock, net.nexus_flow.core.observability.Observability.NO_OP);
    }

    /**
     * Full-fidelity factory with a custom {@link java.time.Clock} and observability sinks ({@link
     * net.nexus_flow.core.observability.Observability}). Adapter modules (Spring, Quarkus,
     * OpenTelemetry, Micrometer) use this overload to wire their native metrics and tracing into the
     * listener executor without depending on those libraries from {@code core}.
     */
    static EventBus newInstance(
            boolean parallelListenersEnabled,
            java.time.Clock clock,
            net.nexus_flow.core.observability.Observability observability) {
        return new DefaultEventBus(parallelListenersEnabled, clock, observability);
    }

    /**
     * Registers a domain-event listener for the specified event type.
     *
     * <p>Multiple listeners can be registered for the same event type and will be invoked in
     * registration order. Listener invocation is synchronous (inline) by default; parallelism is
     * opt-in via {@link #newInstance(boolean)}.
     *
     * @param <E>      the event type (must extend {@link DomainEvent})
     * @param listener the listener to register
     */
    <E extends DomainEvent> void register(DomainEventListener<E> listener);

    /**
     * Unregisters a domain-event listener.
     *
     * @param <E>      the event type (must extend {@link DomainEvent})
     * @param listener the listener to unregister
     */
    <E extends DomainEvent> void unregister(DomainEventListener<E> listener);

    /**
     * Dispatches an event to all registered listeners.
     *
     * <p>Synchronously invokes listeners in registration order. Domain events are fanned out with the
     * given saga context (whether this dispatch originates from a saga invocation).
     *
     * @param <E>    the event type (must extend {@link DomainEvent})
     * @param event  the event to dispatch
     * @param isSaga {@code true} if dispatched from a saga context, {@code false} otherwise
     */
    <E extends DomainEvent> void dispatch(E event, boolean isSaga);

    /**
     * Structured result-based dispatch. Synchronously fans out to every registered listener of {@code
     * event} under {@code policy} and returns a {@link DispatchResult}. The legacy {@link
     * #dispatch(DomainEvent, boolean)} keeps its current behavior and contract; this method is
     * additive.
     */
    <E extends DomainEvent> DispatchResult<Void> dispatchResult(
            E event, ExecutionContext ctx, ErrorPolicy policy);

    /** Convenience overload — root context, FailFast policy. */
    default <E extends DomainEvent> DispatchResult<Void> dispatchResult(E event) {
        return dispatchResult(event, ExecutionContext.root(), ErrorPolicy.failFast());
    }

    /**
     * drain every registered listener. Called from {@code FlowRuntime#close()}; not intended for
     * direct use.
     */
    void closeAll();

    /**
     * Pauses dispatch to the given listener. While paused, events for this listener are silently
     * dropped (filter-skip semantics). Does not affect other listeners. No-op if the listener is not
     * registered or already paused.
     */
    void pause(DomainEventListener<?> listener);

    /** Resumes a previously paused listener. No-op if not paused. */
    void resume(DomainEventListener<?> listener);

    /** Returns {@code true} if the given listener is currently paused. */
    boolean isPaused(DomainEventListener<?> listener);

    /**
     * Returns a snapshot of per-listener runtime statistics. The returned map reflects state at call
     * time; subsequent invocations may return different values.
     */
    EventBusStats stats();

    /**
     * Configures the {@link DeadLetterQueue} for this bus. Events that exhaust all retry attempts and
     * error-handler options are enqueued here instead of propagating an exception. Pass {@code null}
     * to remove the DLQ and revert to exception propagation.
     */
    void deadLetterQueue(DeadLetterQueue dlq);

    /**
     * Configures publish back-pressure for this bus. Constrains how many concurrent {@link
     * #dispatchResult} calls are allowed. Default: {@link
     * EventPublishBackpressureSettings#UNLIMITED}.
     */
    void publishBackpressure(EventPublishBackpressureSettings settings);

    /**
     * diagnostic snapshot of registered event listeners. Mirrors the shape of {@link
     * net.nexus_flow.core.cqrs.command.CommandBus#registrationSnapshot()
     * CommandBus.registrationSnapshot()} on the event side so observability tooling sees a unified
     * view across all dispatch targets on the runtime.
     *
     * <p>The map value is the number of listeners registered for that event class. Concurrent
     * register / unregister after the snapshot is taken does not mutate the returned record.
     */
    EventRegistrationSnapshot registrationSnapshot();
}

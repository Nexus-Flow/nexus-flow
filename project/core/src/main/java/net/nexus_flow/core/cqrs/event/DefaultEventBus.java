package net.nexus_flow.core.cqrs.event;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.event.exceptions.EventPublishRejectedException;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.observability.jfr.EventPublishEvent;
import net.nexus_flow.core.observability.jfr.HandlerInvokeEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.PerRuntime;
import net.nexus_flow.core.runtime.dispatch.SyncDispatcher;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.registry.DispatchPlan;
import net.nexus_flow.core.runtime.registry.HandlerInvoker;
import net.nexus_flow.core.runtime.registry.HandlerRegistry;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowInterruptedException;
import net.nexus_flow.core.types.TypeReference;
import org.jspecify.annotations.Nullable;

/**
 * Per-runtime default implementation of {@link EventBus}.
 *
 * <p><b>migration (this revision).</b> Listener storage moved from the legacy {@code
 * DefaultEventPublisherRegistry} + {@code DefaultEventPublisher} pair to the runtime-owned {@link
 * HandlerRegistry}. Concretely:
 *
 * <ul>
 * <li>{@link #register(DomainEventListener)} calls {@code MethodHandles.Lookup.unreflect} on the
 * listener's {@code handle(E)} method once and stores a {@link
 * java.lang.invoke.MethodHandle}-backed {@link HandlerInvoker} (). Reflective fallback is
 * wired in {@code MethodHandleHandlerInvoker.unreflectOrFallback}.
 * <li>{@link #dispatchResult(DomainEvent, ExecutionContext, ErrorPolicy)} reads a {@code
 *       ClassValue}-cached {@link DispatchPlan} via {@link HandlerRegistry#planFor(Class)} () and
 * iterates the invokers <em>sequentially in registration order</em>, preserving the ordering
 * invariant pinned by {@code EventFanOutListenersInRegistrationOrderTest} and {@code
 *       EventOrderingByAggregateRecordingTest}.
 * <li>The legacy {@link #dispatch(DomainEvent, boolean)} entry point (still called by the command
 * executor) now also goes through the same registry; it is a thin synchronous fan-out without
 * a structured per-listener result.
 * <li>Plan ordering inside the registry sorts by ascending {@code order()} priority and then by
 * registration FIFO for stable tie-breaking ().
 * </ul>
 *
 * <p>Parallel listener invocation is explicitly deferred: opt-in parallelism is only safe once a
 * per-listener idempotency contract is wired up.
 */
@PerRuntime
non-sealed class DefaultEventBus implements EventBus {

    /**
     * Bound abstract method handle every listener inherits from {@link AbstractDomainEventListener}.
     * Resolved once and shared across registrations; {@link HandlerInvoker} dispatch is virtual so
     * the override in the concrete subclass is reached.
     */
    private static final Method LISTENER_HANDLE_METHOD;

    private static final System.Logger LOG = System.getLogger(DefaultEventBus.class.getName());

    static {
        try {
            LISTENER_HANDLE_METHOD =
                    AbstractDomainEventListener.class.getDeclaredMethod("handle", DomainEvent.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final HandlerRegistry<DomainEvent, Void>                              registry              = new HandlerRegistry<>();
    private final ConcurrentHashMap<DomainEventListener<?>, ListenerRegistration> listenerRegistrations = new ConcurrentHashMap<>();

    /** opt-in parallel fan-out gate. */
    private final boolean parallelListenersEnabled;

    /**
     * injectable wall-clock used wherever the bus needs a timestamp (currently: dead-letter
     * envelopes). Defaults to {@link java.time.Clock#systemUTC()}. Spring/Quarkus/Micronaut adapters
     * can inject the framework-provided {@code Clock} bean so test harnesses get deterministic
     * timestamps without monkey-patching {@code Instant.now}.
     */
    private final java.time.Clock clock;

    /**
     * observability sinks (metrics + tracing). Propagated to every {@link ListenerExecutor} created
     * by this bus so adapter modules can record retries, dead-letter routing, rate-limit drops and
     * deduplicator hits at their native registry.
     */
    private final net.nexus_flow.core.observability.Observability observability;

    /**
     * Atomic pair of backpressure settings + the semaphore derived from them. Grouping both into a
     * single reference prevents a torn read where one thread sees the new settings but the old
     * semaphore (or vice versa).
     */
    private record BackpressureState(
                                     EventPublishBackpressureSettings settings, java.util.concurrent.Semaphore semaphore) {

        static final BackpressureState UNLIMITED =
                new BackpressureState(EventPublishBackpressureSettings.UNLIMITED, null);
    }

    private final AtomicReference<BackpressureState> backpressure =
            new AtomicReference<>(BackpressureState.UNLIMITED);

    private final AtomicReference<@Nullable DeadLetterQueue> deadLetterQueueRef =
            new AtomicReference<>();

    /**
     * One executor per registered listener instance. Created at register time, released at
     * unregister/closeAll.
     */
    private final ConcurrentHashMap<DomainEventListener<?>, ListenerExecutor<?>> listenerExecutors =
            new ConcurrentHashMap<>();

    private record ListenerRegistration(
                                        Class<? extends DomainEvent> eventClass, HandlerInvoker<DomainEvent, Void> invoker) {
    }

    DefaultEventBus() {
        this(false, java.time.Clock.systemUTC(), net.nexus_flow.core.observability.Observability.NO_OP);
    }

    DefaultEventBus(boolean parallelListenersEnabled) {
        this(
             parallelListenersEnabled,
             java.time.Clock.systemUTC(),
             net.nexus_flow.core.observability.Observability.NO_OP);
    }

    DefaultEventBus(boolean parallelListenersEnabled, java.time.Clock clock) {
        this(parallelListenersEnabled, clock, net.nexus_flow.core.observability.Observability.NO_OP);
    }

    DefaultEventBus(
            boolean parallelListenersEnabled,
            java.time.Clock clock,
            net.nexus_flow.core.observability.Observability observability) {
        this.parallelListenersEnabled = parallelListenersEnabled;
        this.clock                    = java.util.Objects.requireNonNull(clock, "clock");
        this.observability            = java.util.Objects.requireNonNull(observability, "observability");
    }

    /** {@inheritDoc} */
    @Override
    public void closeAll() {
        registry.clear();
        listenerRegistrations.clear();
        listenerExecutors.clear();
    }

    /**
     * Cached {@link EventRegistrationSnapshot}, invalidated by register / unregister. The
     * snapshot is read-mostly: dashboards / health probes hit it often, but the registry
     * mutates on bootstrap and rarely after. Caching keeps the hot read at a single volatile
     * read.
     */
    private volatile @org.jspecify.annotations.Nullable EventRegistrationSnapshot cachedRegistrationSnapshot;

    /** {@inheritDoc} */
    @Override
    public EventRegistrationSnapshot registrationSnapshot() {
        EventRegistrationSnapshot cached = cachedRegistrationSnapshot;
        if (cached != null) {
            return cached;
        }
        java.util.Map<Class<? extends DomainEvent>, Integer> map = new java.util.LinkedHashMap<>();
        for (Class<?> raw : registry.registeredTypes()) {
            @SuppressWarnings("unchecked") Class<? extends DomainEvent> ec    = (Class<? extends DomainEvent>) raw;
            int                                                         count = registry.planFor(ec).size();
            if (count > 0) {
                map.put(ec, count);
            }
        }
        EventRegistrationSnapshot fresh = new EventRegistrationSnapshot(map);
        cachedRegistrationSnapshot = fresh;
        return fresh;
    }

    /** {@inheritDoc} */
    @Override
    public <E extends DomainEvent> void register(DomainEventListener<E> listener) {
        Class<? extends DomainEvent> eventClass = eventClassOf(listener);
        switch (listener) {
            case AbstractDomainEventListener<E> abs -> {
                if (parallelListenersEnabled && !abs.parallelSafe()) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            () -> "Listener "
                                    + abs.getClass().getName()
                                    + " is registered for "
                                    + eventClass.getSimpleName()
                                    + " but does not declare parallelSafe()=true. "
                                    + "Parallel fan-out is DISABLED for this event class "
                                    + "as long as any non-parallel-safe listener is registered. "
                                    + "Override parallelSafe() to opt in.");
                }
                if (abs instanceof PartitionedEventListener<?> partitioned) {
                    // The bus now routes by partitionKey × partitionIndex (see PartitionedEventListener for
                    // the contract). The check is enforced inside ListenerExecutor. We still log at INFO so
                    // operators can confirm the partition wiring at startup.
                    LOG.log(
                            System.Logger.Level.INFO,
                            () -> "Listener "
                                    + abs.getClass().getName()
                                    + " registered as PartitionedEventListener for "
                                    + eventClass.getSimpleName()
                                    + " owning partition "
                                    + partitioned.partitionIndex()
                                    + " of "
                                    + partitioned.partitionCount()
                                    + ". Sibling instances must use the same partitionCount and distinct "
                                    + "partitionIndex values to cover every slot exactly once.");
                }

                HandlerInvoker<DomainEvent, Void> base = registry.buildInvoker(abs, LISTENER_HANDLE_METHOD);

                ListenerExecutor<E> executor =
                        new ListenerExecutor<>(listener, deadLetterQueueRef::get, clock, observability);
                listenerExecutors.put(listener, executor);

                HandlerInvoker<DomainEvent, Void> wrapped = executor.toInvoker(base);

                registry.registerInvoker(eventClass, wrapped, abs.order(), abs.parallelSafe());

                ListenerRegistration previous =
                        listenerRegistrations.put(listener, new ListenerRegistration(eventClass, wrapped));
                if (previous != null) {
                    registry.unregisterInvoker(previous.eventClass(), previous.invoker());
                }
                cachedRegistrationSnapshot = null;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public <E extends DomainEvent> void unregister(DomainEventListener<E> listener) {
        switch (listener) {
            case AbstractDomainEventListener<E> abs -> {
                ListenerRegistration reg = listenerRegistrations.remove(abs);
                if (reg == null) {
                    return;
                }
                registry.unregisterInvoker(reg.eventClass(), reg.invoker());
                listenerExecutors.remove(abs);
                cachedRegistrationSnapshot = null;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public <E extends DomainEvent> void dispatch(E event, boolean isSaga) {
        // legacy entry point. Sequential fan-out through the
        // HandlerRegistry. The isSaga flag is preserved for ABI
        // stability but no longer changes the dispatch path (saga and
        // non-saga both run inline on the caller; matches// and thecleanup of the legacy
        // DefaultEventPublisher).
        DispatchPlan<DomainEvent, Void> plan = registry.planFor(event.getClass());
        if (plan.isEmpty()) {
            return;
        }
        // JFR fan-out event. The legacy path is still
        // the production routing for the no-outbox case
        // (see HandlerEventDrain.drain), so emitting only from
        // dispatchResult would miss the common case.
        EventPublishEvent jfr = new EventPublishEvent();
        jfr.begin();

        // Best-effort: collect listener failures and surface them at
        // the end as a wrapped RuntimeException so this fire-and-forget
        // path never silently swallows. The structured per-listener
        // contract lives on dispatchResult below.
        List<Throwable> failures = null;
        for (HandlerInvoker<DomainEvent, Void> inv : plan.handlers()) {
            try {
                invokeWithJfr(inv, event, /* ctx= */ null);
            } catch (Throwable t) {
                if (failures == null) {
                    failures = new ArrayList<>();
                }
                failures.add(t);
            }
        }

        jfr.end();
        if (jfr.shouldCommit()) {
            jfr.eventType      = net.nexus_flow.core.runtime.ClassNameCache.get(event.getClass());
            jfr.listenerCount  = plan.size();
            jfr.parallelFanOut = false;
            if (failures == null) {
                jfr.outcome      = "Success";
                jfr.failureClass = null;
            } else {
                jfr.outcome      = failures.size() == plan.size() ? "Failure" : "PartialFailure";
                jfr.failureClass = net.nexus_flow.core.runtime.ClassNameCache.get(failures.getFirst().getClass());
            }
            jfr.commit();
        }

        if (failures != null) {
            RuntimeException agg =
                    new RuntimeException(
                            "One or more event listeners failed for event "
                                    + event.getClass().getName()
                                    + " (aggregateId="
                                    + event.getAggregateId()
                                    + ")",
                            failures.getFirst());
            for (int i = 1; i < failures.size(); i++) {
                // ThrowableUtils.withSuppressed guards against null + self-suppress; matches the
                // framework-wide convention for attaching secondary throwables.
                net.nexus_flow.core.runtime.ThrowableUtils.withSuppressed(agg, failures.get(i));
            }
            throw agg;
        }
    }

    /** {@inheritDoc} */
    @Override
    public <E extends DomainEvent> DispatchResult<Void> dispatchResult(
            E event, ExecutionContext ctx, ErrorPolicy policy) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(policy, "policy");

        BackpressureState              bp       = this.backpressure.get();
        java.util.concurrent.Semaphore pubSem   = bp.semaphore();
        boolean                        acquired = false;
        if (pubSem != null) {
            switch (bp.settings().policy()) {
                case BLOCK_CALLER -> {
                    try {
                        pubSem.acquire();
                        acquired = true;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new FlowInterruptedException("EventBus publish interrupted by backpressure", ie);
                    }
                }
                case DROP         -> {
                    if (!pubSem.tryAcquire()) {
                        return DispatchResult.success(null);
                    }
                    acquired = true;
                }
                case REJECT       -> {
                    if (!pubSem.tryAcquire()) {
                        throw new EventPublishRejectedException(bp.settings().maxConcurrentDispatches());
                    }
                    acquired = true;
                }
            }
        }

        try {
            DispatchPlan<DomainEvent, Void> plan = registry.planFor(event.getClass());
            if (plan.isEmpty()) {
                return DispatchResult.success(null);
            }

            // JFR custom event around the whole fan-out.
            // begin()/end() must always be paired; end() and the shouldCommit() gate
            // live in the finally block so a rare Error propagation (OOM, etc.) still
            // closes the recording slot correctly instead of leaking an open event.
            EventPublishEvent jfr = new EventPublishEvent();
            jfr.begin();

            // opt-in parallel fan-out gate.
            boolean              parallel  = parallelListenersEnabled && plan.allParallelSafe() && plan.size() > 1;
            DispatchResult<Void> result    = null;
            Throwable            jfrThrown = null;
            try {
                result =
                        parallel ? dispatchParallel(event, ctx, policy, plan) : dispatchSequential(event, ctx, policy, plan);
                return result;
            } catch (Throwable t) {
                jfrThrown = t;
                throw t;
            } finally {
                jfr.end();
                if (jfr.shouldCommit()) {
                    jfr.eventType      = net.nexus_flow.core.runtime.ClassNameCache.get(event.getClass());
                    jfr.listenerCount  = plan.size();
                    jfr.parallelFanOut = parallel;
                    if (jfrThrown != null) {
                        jfr.outcome      = "Failure";
                        jfr.failureClass = net.nexus_flow.core.runtime.ClassNameCache.get(jfrThrown.getClass());
                    } else if (result != null) {
                        jfr.outcome      = outcomeOf(result);
                        jfr.failureClass = failureClassOf(result);
                    }
                    jfr.commit();
                }
            }
        } finally {
            if (pubSem != null && acquired) {
                pubSem.release();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void pause(DomainEventListener<?> listener) {
        ListenerExecutor<?> ex = listenerExecutors.get(listener);
        if (ex != null) {
            ex.pause();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void resume(DomainEventListener<?> listener) {
        ListenerExecutor<?> ex = listenerExecutors.get(listener);
        if (ex != null) {
            ex.resume();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPaused(DomainEventListener<?> listener) {
        ListenerExecutor<?> ex = listenerExecutors.get(listener);
        return ex != null && ex.isPaused();
    }

    /** {@inheritDoc} */
    @Override
    public EventBusStats stats() {
        java.util.Map<Class<?>, ListenerStats> snapshot = new java.util.LinkedHashMap<>();
        listenerExecutors.forEach(
                                  (listener, executor) -> snapshot.merge(
                                                                         executor.listenerClass(), executor.stats().copy(),
                                                                         DefaultEventBus::mergeStats));
        return new EventBusStats(snapshot);
    }

    /** {@inheritDoc} */
    @Override
    public void deadLetterQueue(DeadLetterQueue dlq) {
        this.deadLetterQueueRef.set(dlq);
    }

    /** {@inheritDoc} */
    @Override
    public void publishBackpressure(EventPublishBackpressureSettings settings) {
        java.util.Objects.requireNonNull(settings, "settings");
        java.util.concurrent.Semaphore sem =
                settings.isUnlimited() ? null : new java.util.concurrent.Semaphore(settings.maxConcurrentDispatches(), true);
        this.backpressure.set(new BackpressureState(settings, sem));
    }

    private static ListenerStats mergeStats(ListenerStats left, ListenerStats right) {
        return left.add(right);
    }

    private DispatchResult<Void> dispatchSequential(
            DomainEvent event,
            ExecutionContext ctx,
            ErrorPolicy policy,
            DispatchPlan<DomainEvent, Void> plan) {
        // Listeners run SEQUENTIALLY in registration order, NEVER in parallel. Listener L_(k+1)
        // only starts after L_k has returned. This is the precondition for the durable outbox: a
        // persisting listener registered last observes every previously-applied listener's side
        // effects before it has to ship the event.
        //
        // Allocation discipline (hot path — fired per event × per dispatch):
        //   - failure accumulator is INLINED: head + lazy list, zero allocation in the
        //     happy path (most events have zero listener failures).
        //   - Listener invocation lambda is replaced with a nominal class
        //     {@link SequentialListenerCall} so the JIT's call-site profiles are
        //     deterministic — lambda metafactory synthetic classes can confuse
        //     escape-analysis under heavy fan-out.
        //
        // Error-policy folding mirrors SyncDispatcher.fanOut at the listener granularity,
        // inlined here so the happy path does not pay for a method call + accumulator allocation.
        Throwable       firstFailure       = null;
        List<Throwable> additionalFailures = null;
        for (HandlerInvoker<DomainEvent, Void> invoker : plan.handlers()) {
            try {
                ctx.throwIfCancelledOrExpired();
            } catch (Throwable t) {
                DispatchResult<Void> cls = SyncDispatcher.classify(t, ctx, policy);
                // Cancel/deadline mid-fan-out: surface immediately with whatever failures
                // we already collected as suppressed context.
                return foldTerminal(cls, firstFailure, additionalFailures);
            }
            ExecutionContext     childCtx = ctx.childContextFor(MessageId.random());
            DispatchResult<Void> listenerResult;
            try {
                FlowScope.runWithContext(childCtx, new SequentialListenerCall(invoker, event, childCtx));
                listenerResult = DispatchResult.success(null);
            } catch (Throwable t) {
                listenerResult = SyncDispatcher.classify(t, ctx, policy);
            }

            switch (listenerResult) {
                case DispatchResult.Success<Void> _        -> {
                    /* no-op; happy path, no allocation */
                }
                case DispatchResult.Accepted<Void> _       -> {
                    /* Accepted ≡ Success for event fan-out (outbox worker owns delivery) */
                }
                case DispatchResult.Failure<Void> f        -> {
                    Throwable cause = f.cause();
                    switch (policy) {
                        case ErrorPolicy.FailFast _                                         -> {
                            return DispatchResult.failure(cause);
                        }
                        case ErrorPolicy.IgnoreFailures ig                                  -> {
                            if (!ig.predicate().test(cause)) {
                                return DispatchResult.failure(cause);
                            }
                            // predicate matched — drop silently
                        }
                        case ErrorPolicy.CollectFailures _,ErrorPolicy.IsolatePerBoundary _ -> {
                            if (firstFailure == null) {
                                firstFailure = cause;
                            } else {
                                if (additionalFailures == null) {
                                    additionalFailures = new ArrayList<>(2);
                                }
                                additionalFailures.add(cause);
                            }
                        }
                    }
                }
                case DispatchResult.PartialFailure<Void> p -> {
                    for (Throwable cause : p.failures()) {
                        if (firstFailure == null) {
                            firstFailure = cause;
                        } else {
                            if (additionalFailures == null) {
                                additionalFailures = new ArrayList<>(Math.max(2, p.failures().size()));
                            }
                            additionalFailures.add(cause);
                        }
                    }
                }
            }
        }

        if (firstFailure == null) {
            return DispatchResult.success(null);
        }
        if (additionalFailures == null) {
            return DispatchResult.partial(null, List.of(firstFailure));
        }
        // Multi-failure case — one allocation for the combined list (genuinely unavoidable).
        List<Throwable> all = new ArrayList<>(1 + additionalFailures.size());
        all.add(firstFailure);
        all.addAll(additionalFailures);
        return DispatchResult.partial(null, all);
    }

    /**
     * Build the terminal DispatchResult that surfaces a mid-fan-out cancel/deadline along with any
     * failures already collected. Used by {@code dispatchSequential} when the cancellation guard
     * trips between listeners.
     *
     * <p>The collected listener failures are attached as suppressed exceptions on the terminal cause
     * so observability tooling that inspects {@link Throwable#getSuppressed()} surfaces every
     * listener exception, not just the cancellation that won the race. This preserves the "no silent
     * loss of failure info" invariant — observability layers must always see every contributing
     * exception.
     */
    private static DispatchResult<Void> foldTerminal(
            DispatchResult<Void> terminal,
            @Nullable Throwable firstFailure,
            @Nullable List<Throwable> additionalFailures) {
        if (firstFailure == null) {
            return terminal;
        }
        if (terminal instanceof DispatchResult.Failure<Void> f) {
            Throwable                    cause             = f.cause();
            DispatchResult.Failure<Void> effectiveTerminal = f;
            // {@link FlowCancellationException#CANCELLED} is a shared singleton with
            // {@code enableSuppression=false} — attaching listener failures to it would be a
            // silent no-op AND, since it is shared across threads, attaching to it would also
            // be a cross-thread-mutation bug if suppression were enabled. When we have
            // failures to attach AND the terminal carries the singleton, swap to a fresh
            // {@link FlowCancellationException} with suppression enabled. The hot path (no
            // failures to attach) keeps the singleton.
            if (cause == net.nexus_flow.core.runtime.result.FlowCancellationException.CANCELLED) {
                cause             = new net.nexus_flow.core.runtime.result.FlowCancellationException();
                effectiveTerminal = new DispatchResult.Failure<>(cause);
            }
            // Convention: route every cross-failure attachment through ThrowableUtils.
            net.nexus_flow.core.runtime.ThrowableUtils.withSuppressed(cause, firstFailure);
            if (additionalFailures != null) {
                for (Throwable extra : additionalFailures) {
                    net.nexus_flow.core.runtime.ThrowableUtils.withSuppressed(cause, extra);
                }
            }
            return effectiveTerminal;
        }
        return terminal;
    }

    /**
     * Nominal-class replacement for the per-listener invocation lambda. Identical observable
     * semantics; explicit class makes the call site monomorphic and the captures field-typed so the
     * JIT does not have to thread through the LambdaMetafactory synthetic class on every fan-out
     * iteration.
     */
    private static final class SequentialListenerCall implements Runnable {
        private final HandlerInvoker<DomainEvent, Void> invoker;
        private final DomainEvent                       event;
        private final ExecutionContext                  childCtx;

        SequentialListenerCall(
                HandlerInvoker<DomainEvent, Void> invoker, DomainEvent event, ExecutionContext childCtx) {
            this.invoker  = invoker;
            this.event    = event;
            this.childCtx = childCtx;
        }

        @Override
        public void run() {
            try {
                invokeWithJfr(invoker, event, childCtx);
            } catch (RuntimeException re) {
                throw re;
            } catch (Throwable e) {
                throw new ListenerInvocationException(e);
            }
        }
    }

    /**
     * concurrent fan-out path used only when every listener of {@code event}'s concrete class has
     * opted into {@link EventListener#parallelSafe()}. The dispatcher's {@link
     * SyncDispatcher#fanOut(List, ExecutionContext, ErrorPolicy)} already handles each {@link
     * ErrorPolicy} variant; we wrap each invoker in a {@link Callable} that scopes a child {@link
     * FlowScope} per listener so per-listener context lookups still work concurrently.
     */
    private DispatchResult<Void> dispatchParallel(
            DomainEvent event,
            ExecutionContext ctx,
            ErrorPolicy policy,
            DispatchPlan<DomainEvent, Void> plan) {
        // The tasks list is pre-sized to plan.size() — no growth resizes. Each task is a
        // ParallelListenerCall nominal class (not a lambda) so the JIT sees a stable type at the
        // structured-task-scope fork point.
        List<java.util.concurrent.Callable<Void>> tasks = new ArrayList<>(plan.size());
        for (HandlerInvoker<DomainEvent, Void> invoker : plan.handlers()) {
            ExecutionContext childCtx = ctx.childContextFor(MessageId.random());
            tasks.add(new ParallelListenerCall(invoker, event, childCtx));
        }
        return SyncDispatcher.fanOut(tasks, ctx, policy);
    }

    /**
     * Nominal-class replacement for the parallel-dispatch lambda. Same observable semantics as the
     * sequential variant but returns {@code null} from {@code call()} so it can plug into {@link
     * SyncDispatcher#fanOut(List, ExecutionContext, ErrorPolicy)}'s {@link
     * java.util.concurrent.Callable} list. Explicit class for deterministic JIT inlining across many
     * structured-task-scope forks.
     */
    private static final class ParallelListenerCall implements java.util.concurrent.Callable<Void> {
        private final HandlerInvoker<DomainEvent, Void> invoker;
        private final DomainEvent                       event;
        private final ExecutionContext                  childCtx;

        ParallelListenerCall(
                HandlerInvoker<DomainEvent, Void> invoker, DomainEvent event, ExecutionContext childCtx) {
            this.invoker  = invoker;
            this.event    = event;
            this.childCtx = childCtx;
        }

        @Override
        public Void call() {
            FlowScope.runWithContext(childCtx, new SequentialListenerCall(invoker, event, childCtx));
            return null;
        }
    }

    // foldListenerOutcome was inlined into dispatchSequential as part of the zero-alloc
    // happy-path refactor. The branch coverage is identical; the inlined version avoids the
    // List<Throwable> accumulator allocation and a per-listener method call.

    /**
     * emit a JFR {@link HandlerInvokeEvent} around a single listener call. The event is begun
     * unconditionally (cheap), but field assignment and {@link jdk.jfr.Event#commit()} are gated on
     * {@link jdk.jfr.Event#shouldCommit()} so the runtime can suppress the event when no recording is
     * active. Re-throws every throwable verbatim — the caller's {@link SyncDispatcher#classify} folds
     * it back into the dispatch result.
     */
    private static void invokeWithJfr(
            HandlerInvoker<DomainEvent, Void> invoker, DomainEvent event, ExecutionContext childCtx) throws Throwable {
        HandlerInvokeEvent jfr = new HandlerInvokeEvent();
        jfr.begin();
        Throwable thrown = null;
        try {
            invoker.invoke(event, childCtx);
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            jfr.end();
            if (jfr.shouldCommit()) {
                jfr.targetType   = net.nexus_flow.core.runtime.ClassNameCache.get(event.getClass());
                jfr.handlerType  = invoker.handlerType();
                jfr.success      = (thrown == null);
                jfr.failureClass = (thrown == null) ? null : net.nexus_flow.core.runtime.ClassNameCache.get(thrown.getClass());
                jfr.commit();
            }
        }
    }

    private static String outcomeOf(DispatchResult<?> r) {
        return switch (r) {
            case DispatchResult.Success<?> _        -> "Success";
            case DispatchResult.Failure<?> _        -> "Failure";
            case DispatchResult.PartialFailure<?> _ -> "PartialFailure";
            case DispatchResult.Accepted<?> _       -> "Accepted";
        };
    }

    private static @Nullable String failureClassOf(DispatchResult<?> r) {
        return switch (r) {
            case DispatchResult.Success<?> _,DispatchResult.Accepted<?> _ -> null;
            case DispatchResult.Failure<?> f                              -> f.cause() == null ? null : f.cause().getClass().getName();
            case DispatchResult.PartialFailure<?> p                       ->
                 p.failures().isEmpty() ? null : p.failures().getFirst().getClass().getName();
        };
    }

    @SuppressWarnings("unchecked")
    private static <E extends DomainEvent> Class<? extends DomainEvent> eventClassOf(
            DomainEventListener<E> listener) { // the listener's reified event type is obtained
        // through its EventTypeSignature. If the registered listener
        // somehow carries an unresolved type variable (e.g. built from
        // a buggy generic factory), we surface a clear error here
        // rather than mis-routing silently.
        TypeReference<E> ref = ((AbstractDomainEventListener<E>) listener).getEventType();
        Type             t   = ref.getType();
        if (t instanceof Class<?> c) {
            return (Class<? extends DomainEvent>) c;
        }
        throw new IllegalArgumentException(
                "DomainEventListener must be parameterised with a concrete event class; got " + t);
    }
}

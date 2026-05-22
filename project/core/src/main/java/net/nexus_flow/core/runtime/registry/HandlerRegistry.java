package net.nexus_flow.core.runtime.registry;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * runtime-owned, copy-on-write handler registry with a {@code ClassValue}-cached {@link
 * DispatchPlan} per message class.
 *
 * <p>Hot path:
 *
 * <pre>{@code
 * DispatchPlan<MyEvent, Void> plan = registry.planFor(MyEvent.class);
 * for (HandlerInvoker<MyEvent, Void> h : plan.handlers()) {
 * h.invoke(event, ctx);
 * }
 * }</pre>
 *
 * <p>Properties:
 *
 * <ul>
 * <li><b>Lock-free reads.</b> {@link #planFor(Class)} is a single {@link ClassValue#get(Class)}
 * which is lock-free and weak on the class.
 * <li><b>Copy-on-write writes.</b> {@link #register} and {@link #unregister} build a new {@link
 * RegistrationSnapshot} under an intrinsic lock and then publish it via a {@code volatile}
 * write. The {@code ClassValue} is then rebuilt wholesale (no surgical invalidation of an
 * in-use entry).
 * <li><b>Deterministic ordering.</b> Plans list handlers sorted by ascending {@code order}
 * (primary) and registration sequence (secondary). Identical inputs always produce the same
 * plan list, pinned by {@code DispatchPlanMultiHandlerOrderingIsDeterministicTest}.
 * <li><b>MethodHandle invokers by default.</b> {@link #register(Class, Object, Method, int)}
 * unreflects the handler's {@code handle} method into a {@link java.lang.invoke.MethodHandle}
 * bound to the handler instance. Reflective fallback is wired in {@link
 * MethodHandleHandlerInvoker#unreflectOrFallback}.
 * </ul>
 *
 * <p>Defers parallel dispatch of the plan: the returned plan is a sequential list, and callers
 * iterate it in order to preserve invariants. Opt-in parallelism is available for handlers marked
 * as {@code parallelSafe}.
 *
 * @param <M> upper bound on message types this registry handles (e.g. {@code DomainEvent})
 * @param <R> handler return type (use {@code Void} for void listeners)
 */
public final class HandlerRegistry<M, R> {

    private final Object writeLock = new Object();

    /**
     * Snapshot is rebuilt copy-on-write; AtomicReference so readers always see the latest publish.
     */
    private final AtomicReference<RegistrationSnapshot> snapshot =
            new AtomicReference<>(RegistrationSnapshot.EMPTY);

    /**
     * ClassValue cache. Recreated whole on every snapshot change because the {@code ClassValue} API
     * does not expose a remove operation on JDK 25; replacing the field is the simplest correct way
     * to drop every memoized plan in one shot.
     */
    private final AtomicReference<ClassValue<DispatchPlan<M, R>>> planCache =
            new AtomicReference<>(newCache());

    private ClassValue<DispatchPlan<M, R>> newCache() {
        return new ClassValue<>() {
            @Override
            protected DispatchPlan<M, R> computeValue(Class<?> type) {
                return buildPlan(type, snapshot.get());
            }
        };
    }

    /**
     * Look up (and memoize) the plan for {@code type}. Lock-free. Identical calls return identical
     * instances by reference, which lets callers (and tests) detect cache invalidation via {@code
     * assertSame}.
     */
    public DispatchPlan<M, R> planFor(Class<? extends M> type) {
        return planCache.get().get(Objects.requireNonNull(type, "type"));
    }

    /**
     * Register a handler for messages of {@code type}. The handler's {@code handle} method is
     * unreflected once and bound; subsequent dispatches go through {@link
     * java.lang.invoke.MethodHandle} (or a one-shot logged reflective fallback).
     *
     * @param type         concrete message class
     * @param handler      live handler instance
     * @param handleMethod the {@code handle(M)} method on the handler
     * @param order        priority used for sort; smaller invokes first
     */
    public void register(Class<? extends M> type, Object handler, Method handleMethod, int order) {
        registerAndGetInvoker(type, handler, handleMethod, order);
    }

    /**
     * Register and return the exact invoker instance persisted in the snapshot. Callers can keep it
     * as a token for targeted unregister.
     */
    // Public API: callers may store the invoker token for targeted unregister.
    //noinspection UnusedReturnValue
    public HandlerInvoker<M, R> registerAndGetInvoker(
            Class<? extends M> type, Object handler, Method handleMethod, int order) {
        return registerAndGetInvoker(type, handler, handleMethod, order, /* parallelSafe= */ false);
    }

    /**
     * parallel-aware overload.
     *
     * <p>{@code parallelSafe} is captured into the per-handler {@code RegisteredHandler} record so
     * the downstream {@link DispatchPlan} can derive {@code allParallelSafe} once at build time (zero
     * cost on the hot path).
     */
    public HandlerInvoker<M, R> registerAndGetInvoker(
            Class<? extends M> type,
            Object handler,
            Method handleMethod,
            int order,
            boolean parallelSafe) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(handleMethod, "handleMethod");
        HandlerInvoker<M, R> invoker =
                MethodHandleHandlerInvoker.unreflectOrFallback(handler, handleMethod);
        registerInvoker(type, invoker, order, parallelSafe);
        return invoker;
    }

    /**
     * Builds a {@link HandlerInvoker} for {@code handler}'s {@code handleMethod} WITHOUT registering
     * it. Useful when the caller needs to wrap the invoker before registration.
     *
     * @param handler      handler instance to bind
     * @param handleMethod the {@code handle()} method to unreflect
     * @return a ready-to-use invoker that can be passed to {@link #registerInvoker}
     */
    public HandlerInvoker<M, R> buildInvoker(Object handler, Method handleMethod) {
        return MethodHandleHandlerInvoker.unreflectOrFallback(handler, handleMethod);
    }

    /** Variant for tests / callers that already hold an invoker. */
    public void registerInvoker(Class<? extends M> type, HandlerInvoker<M, R> invoker, int order) {
        registerInvoker(type, invoker, order, /* parallelSafe= */ false);
    }

    /** parallel-aware overload. */
    public void registerInvoker(
            Class<? extends M> type, HandlerInvoker<M, R> invoker, int order, boolean parallelSafe) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(invoker, "invoker");
        synchronized (writeLock) {
            snapshot.set(snapshot.get().withRegistration(type, order, parallelSafe, invoker));
            planCache.set(newCache());
        }
    }

    /** Drop every registration for {@code type} and invalidate the cache. */
    public void unregister(Class<? extends M> type) {
        Objects.requireNonNull(type, "type");
        synchronized (writeLock) {
            RegistrationSnapshot current = snapshot.get();
            RegistrationSnapshot next    = current.withoutType(type);
            if (next == current) {
                return;
            }
            snapshot.set(next);
            planCache.set(newCache());
        }
    }

    /**
     * Drop exactly one invoker from the plan of {@code type}.
     *
     * <p>Used by buses that support unregistering one listener instance while keeping siblings for
     * the same message type.
     */
    public void unregisterInvoker(Class<? extends M> type, HandlerInvoker<M, R> invoker) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(invoker, "invoker");
        synchronized (writeLock) {
            RegistrationSnapshot current = snapshot.get();
            RegistrationSnapshot next    = current.withoutInvoker(type, invoker);
            if (next == current) {
                return;
            }
            snapshot.set(next);
            planCache.set(newCache());
        }
    }

    /** Drop every registration; used by {@code FlowRuntime#close()}. */
    public void clear() {
        synchronized (writeLock) {
            if (snapshot.get() == RegistrationSnapshot.EMPTY) {
                return;
            }
            snapshot.set(RegistrationSnapshot.EMPTY);
            planCache.set(newCache());
        }
    }

    /**
     * Snapshot accessor for diagnostic / test code only.
     *
     * @return the current registration snapshot; never {@code null}. The snapshot is immutable and
     *         may be stale by the time the caller inspects it (a concurrent {@link #register} may have
     *         replaced it). Only use this in single-threaded tests or under external synchronization.
     */
    RegistrationSnapshot snapshotForTesting() {
        return snapshot.get();
    }

    /**
     * public read of every message-class that has at least one registered handler. Returns a
     * defensive copy frozen at the moment of the call; concurrent register / unregister after the
     * call does not mutate the returned set.
     *
     * <p>Intended for {@code EventRegistrationSnapshot} and {@code QueryRegistrationSnapshot} — the
     * event / query side equivalent of {@link
     * net.nexus_flow.core.cqrs.command.CommandRegistrationSnapshot}.
     */
    public java.util.Set<Class<?>> registeredTypes() {
        return java.util.Set.copyOf(snapshot.get().knownTypesSet());
    }

    /**
     * total number of registered message types.
     *
     * @return the number of distinct message classes for which at least one handler is registered; 0
     *         when the registry is empty
     */
    public int registeredTypeCount() {
        return snapshot.get().knownTypesSet().size();
    }

    private DispatchPlan<M, R> buildPlan(Class<?> type, RegistrationSnapshot snap) {
        List<RegistrationSnapshot.RegisteredHandler<M, R>> raw = snap.handlersFor(type);
        if (raw.isEmpty()) {
            return DispatchPlan.empty();
        }
        // sort: order asc, then sequence asc (FIFO tie-break).
        List<RegistrationSnapshot.RegisteredHandler<M, R>> sorted = new ArrayList<>(raw);
        // Explicit type params required: Java cannot infer type for chained thenComparingInt() without
        // them.
        //noinspection RedundantTypeArguments
        sorted.sort(
                    Comparator.comparingInt(RegistrationSnapshot.RegisteredHandler<M, R>::order)
                            .thenComparingInt(RegistrationSnapshot.RegisteredHandler<M, R>::sequence));
        List<HandlerInvoker<M, R>> invokers        = new ArrayList<>(sorted.size());
        boolean                    allParallelSafe = true;
        for (RegistrationSnapshot.RegisteredHandler<M, R> h : sorted) {
            invokers.add(h.invoker());
            if (!h.parallelSafe()) {
                allParallelSafe = false;
            }
        }
        // allParallelSafe = AND of every handler's
        // parallelSafe flag. An empty plan is not parallel-safe in any
        // meaningful sense, but we still set it to false to avoid
        // surprising semantics downstream.
        return new DispatchPlan<>(invokers, allParallelSafe);
    }
}

package net.nexus_flow.core.cqrs.query;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.*;
import net.nexus_flow.core.cqrs.query.exceptions.QueryHandlerExecutionError;
import net.nexus_flow.core.cqrs.query.exceptions.QueryNotRegisteredError;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.PerRuntime;
import net.nexus_flow.core.runtime.ThrowableUtils;
import net.nexus_flow.core.runtime.registry.DispatchPlan;
import net.nexus_flow.core.runtime.registry.HandlerInvoker;
import net.nexus_flow.core.runtime.registry.HandlerRegistry;
import net.nexus_flow.core.types.TypeReference;

/**
 * Per-runtime default implementation of {@link QueryBus}.
 *
 * <p>The previous {@code QueryConsumerRegistry} + {@code DefaultQueryConsumerRegistry} pair (a thin
 * {@code ConcurrentHashMap} keyed by {@link TypeReference}) was replaced by a private {@link
 * HandlerRegistry}. The registry caches the per-class plan in a {@code ClassValue}, making the hot
 * lookup lock-free.
 *
 * <p>Single-handler semantics are preserved: {@link #register(AbstractQueryHandler)} is a no-op
 * when a handler already exists for that query body type, maintaining backward compatibility with
 * the previous {@code putIfAbsent} behavior. Multi-handler queries are intentionally not supported:
 * a query has exactly one authoritative resolver.
 *
 * <p>Why no {@code MethodHandle} invoker here? Each {@code
 * AbstractQueryHandler#getInnerHandler(body)} returns a {@link Callable} that the bus must then
 * {@code call()}. The indirection means the bound invoker is a small lambda over the {@code
 * Callable}; a {@code MethodHandle} would not eliminate that indirection. We still get the
 * lock-free {@link DispatchPlan} lookup and the registry-level ordering invariants for free.
 */
@PerRuntime
class DefaultQueryBus implements QueryBus {

    /**
     * Intrinsic mutex serialising register / unregister. {@link #ask(Query)} does NOT take
     * this lock — it reads through the {@link ClassValue}-cached {@link DispatchPlan} and
     * the lock-free {@link ConcurrentHashMap} plan index. {@code synchronized} on an
     * intrinsic monitor is HotSpot's fastest uncontended-mutex shape (biased lock →
     * thin lock → fat lock adaptive), strictly faster than {@code ReentrantLock} or
     * {@code ReentrantReadWriteLock} when there is no read-side contention to amortise.
     */
    private final Object                          registrationLock = new Object();
    private final HandlerRegistry<Record, Object> registry         = new HandlerRegistry<>();

    /**
     * Per-handler dispatch plan: {@link QuerySettings} + optional {@link Semaphore} bulkhead.
     * Merged from the previous two-map layout so {@link #ask(Query)} pays a single
     * {@link ConcurrentHashMap#get} on the hot path instead of two.
     */
    private record QueryHandlerPlan(QuerySettings settings, @org.jspecify.annotations.Nullable Semaphore bulkhead) {
        static final QueryHandlerPlan NONE = new QueryHandlerPlan(QuerySettings.NONE, null);
    }

    private final java.util.concurrent.ConcurrentHashMap<Class<?>, QueryHandlerPlan> queryHandlerPlanIndex =
            new java.util.concurrent.ConcurrentHashMap<>();

    DefaultQueryBus() {
        // migration: no external registry is required.
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record, R> void register(AbstractQueryHandler<T, R> handler) {
        Objects.requireNonNull(handler, "handler");
        Class<? extends Record>        bodyType = bodyClassOf(handler);
        HandlerInvoker<Record, Object> invoker  =
                (body, ctx) -> {
                                                            @SuppressWarnings("unchecked") T typedBody = (T) body;
                                                            Callable<R>              callable  = handler.getInnerHandler(typedBody);
                                                            return callable.call();
                                                        };
        QuerySettings                  settings = handler.settings();
        synchronized (registrationLock) {
            if (!registry.planFor(bodyType).isEmpty()) {
                return;
            }
            registry.registerInvoker(bodyType, invoker, /* order= */ 0);
            // Unfair semaphore — barging acquires beat FIFO ordering by ~10× under sustained
            // contention. Fair-mode is reserved for cases where starvation is a CORRECTNESS
            // concern; the bulkhead is a CAPACITY gate (drops/queues excess work) and
            // tolerates barging without violating semantics.
            Semaphore bulkhead = settings.hasConcurrencyLimit() ? new Semaphore(settings.maxConcurrent()) : null;
            queryHandlerPlanIndex.put(bodyType, new QueryHandlerPlan(settings, bulkhead));
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record, R> void unregister(AbstractQueryHandler<T, R> handler) {
        Objects.requireNonNull(handler, "handler");
        Class<? extends Record> bodyType = bodyClassOf(handler);
        synchronized (registrationLock) {
            registry.unregister(bodyType);
            queryHandlerPlanIndex.remove(bodyType);
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record, R> R ask(Query<T> query) {
        Objects.requireNonNull(query, "query");
        T                            body     = Objects.requireNonNull(query.getBody(), "query body");
        Class<? extends Record>      bodyType = body.getClass();
        DispatchPlan<Record, Object> plan     = registry.planFor(bodyType);
        if (plan.isEmpty()) {
            throw new QueryNotRegisteredError(query);
        }
        HandlerInvoker<Record, Object> invoker        = plan.handlers().getFirst();
        QueryHandlerPlan               handlerPlan    = queryHandlerPlanIndex.getOrDefault(bodyType, QueryHandlerPlan.NONE);
        QuerySettings                  settings       = handlerPlan.settings();
        ExecutionContext               currentContext = FlowScope.current().orElse(null);
        // Return nullable Duration directly — Optional<Duration> was costing one wrapper
        // allocation per query call on the hot path. {@code null} == no timeout.
        Duration  timeout  = effectiveTimeoutOrNull(settings, currentContext);
        Semaphore bulkhead = handlerPlan.bulkhead();
        if (bulkhead != null) {
            try {
                bulkhead.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new QueryHandlerExecutionError(
                        new RuntimeException("Query bulkhead interrupted", ie));
            }
        }
        try {
            if (timeout == null) {
                try {
                    @SuppressWarnings("unchecked") R result = (R) invokeWithContext(invoker, body, currentContext);
                    return result;
                } catch (Exception e) {
                    throw new QueryHandlerExecutionError(e);
                } catch (Throwable t) {
                    throw new QueryHandlerExecutionError(new RuntimeException(t));
                }
            }

            long                      timeoutNanos = Math.max(0L, timeout.toNanos());
            CompletableFuture<Object> future       =
                    CompletableFuture.supplyAsync(
                                                  () -> {
                                                      try {
                                                          return invokeWithContext(invoker, body, currentContext);
                                                      } catch (Throwable t) {
                                                          throw new QueryHandlerExecutionError(t);
                                                      }
                                                  })
                            .orTimeout(timeoutNanos, TimeUnit.NANOSECONDS);
            try {
                @SuppressWarnings("unchecked") R result = (R) future.join();
                return result;
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof Exception ex) {
                    throw ThrowableUtils.withSuppressed(new QueryHandlerExecutionError(ex), ce);
                }
                throw ThrowableUtils.withSuppressed(
                                                    new QueryHandlerExecutionError(new RuntimeException(cause)), ce);
            }
        } finally {
            if (bulkhead != null) {
                bulkhead.release();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public QueryRegistrationSnapshot registrationSnapshot() {
        return new QueryRegistrationSnapshot(registry.registeredTypes());
    }

    /**
     * Nullable variant of the timeout resolution — returns the effective {@link Duration} or
     * {@code null} when neither the handler config nor the context's deadline imposes a bound.
     * Replaces the prior {@code Optional<Duration>} shape on the {@link #ask} hot path: at
     * 1 M query/sec the per-call Optional wrapper allocation (~3 ns) becomes a measurable
     * pressure source on the dispatch budget.
     */
    private static @org.jspecify.annotations.Nullable Duration effectiveTimeoutOrNull(
            QuerySettings settings, ExecutionContext currentContext) {
        Duration configuredTimeout = settings.timeout();
        Duration deadlineTimeout   = deadlineTimeoutOrNull(currentContext);
        if (configuredTimeout != null && deadlineTimeout != null) {
            return configuredTimeout.compareTo(deadlineTimeout) <= 0 ? configuredTimeout : deadlineTimeout;
        }
        return configuredTimeout != null ? configuredTimeout : deadlineTimeout;
    }

    private static @org.jspecify.annotations.Nullable Duration deadlineTimeoutOrNull(
            ExecutionContext currentContext) {
        if (currentContext == null || !currentContext.hasDeadline()) {
            return null;
        }
        Duration remaining = Duration.between(Instant.now(), currentContext.deadline());
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private static Object invokeWithContext(
            HandlerInvoker<Record, Object> invoker, Record body, ExecutionContext currentContext) throws Throwable {
        if (currentContext == null || FlowScope.current().orElse(null) == currentContext) {
            return invoker.invoke(body, /* ctx= */ null);
        }
        return ScopedValue.where(FlowScope.CURRENT_CONTEXT, currentContext)
                .call(() -> invoker.invoke(body, /* ctx= */ null));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record, R> Class<? extends Record> bodyClassOf(
            AbstractQueryHandler<T, R> handler) {
        TypeReference<T> ref  = handler.getQueryType();
        Type             type = ref.getType();
        if (type instanceof Class<?> klass) {
            return (Class<? extends Record>) klass;
        }
        throw new IllegalArgumentException(
                "QueryHandler must be parameterised with a concrete query body class; got " + type);
    }
}

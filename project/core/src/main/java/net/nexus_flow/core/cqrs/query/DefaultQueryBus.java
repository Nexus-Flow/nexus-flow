package net.nexus_flow.core.cqrs.query;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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

    private final Object                                                          registrationLock  = new Object();
    private final HandlerRegistry<Record, Object>                                 registry          = new HandlerRegistry<>();
    private final java.util.concurrent.ConcurrentHashMap<Class<?>, QuerySettings> handlerSettings   =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Class<?>, Semaphore>     handlerSemaphores =
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
            handlerSettings.put(bodyType, settings);
            if (settings.hasConcurrencyLimit()) {
                handlerSemaphores.put(bodyType, new Semaphore(settings.maxConcurrent(), true));
            } else {
                handlerSemaphores.remove(bodyType);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record, R> void unregister(AbstractQueryHandler<T, R> handler) {
        Objects.requireNonNull(handler, "handler");
        Class<? extends Record> bodyType = bodyClassOf(handler);
        synchronized (registrationLock) {
            registry.unregister(bodyType);
            handlerSettings.remove(bodyType);
            handlerSemaphores.remove(bodyType);
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
        QuerySettings                  settings       = handlerSettings.getOrDefault(bodyType, QuerySettings.NONE);
        ExecutionContext               currentContext = FlowScope.current().orElse(null);
        Optional<Duration>             timeout        = effectiveTimeout(settings, currentContext);
        Semaphore                      bulkhead       = handlerSemaphores.get(bodyType);
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
            if (timeout.isEmpty()) {
                try {
                    @SuppressWarnings("unchecked") R result = (R) invokeWithContext(invoker, body, currentContext);
                    return result;
                } catch (Exception e) {
                    throw new QueryHandlerExecutionError(e);
                } catch (Throwable t) {
                    throw new QueryHandlerExecutionError(new RuntimeException(t));
                }
            }

            long                      timeoutNanos = Math.max(0L, timeout.get().toNanos());
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

    private static Optional<Duration> effectiveTimeout(
            QuerySettings settings, ExecutionContext currentContext) {
        Optional<Duration> configuredTimeout = settings.timeoutOptional();
        Optional<Duration> deadlineTimeout   = deadlineTimeout(currentContext);
        if (configuredTimeout.isPresent() && deadlineTimeout.isPresent()) {
            return Optional.of(min(configuredTimeout.get(), deadlineTimeout.get()));
        }
        return configuredTimeout.isPresent() ? configuredTimeout : deadlineTimeout;
    }

    private static Optional<Duration> deadlineTimeout(ExecutionContext currentContext) {
        if (currentContext == null || !currentContext.hasDeadline()) {
            return Optional.empty();
        }
        Duration remaining = Duration.between(Instant.now(), currentContext.deadline());
        return Optional.of(remaining.isNegative() ? Duration.ZERO : remaining);
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
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

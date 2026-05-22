package net.nexus_flow.core.cqrs.event;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ThrowableUtils;
import net.nexus_flow.core.runtime.registry.HandlerInvoker;
import net.nexus_flow.core.runtime.result.FlowInterruptedException;
import org.jspecify.annotations.Nullable;

/**
 * Per-listener execution envelope that wraps a base {@link HandlerInvoker} with all cross-cutting
 * concerns: pause check, rate limiting, deduplication, filter predicate, concurrency limit, retry
 * loop, error handler, and dead-letter routing.
 *
 * <p>Analogous in purpose to {@code DefaultCommandHandlerExecutor} for commands, but simpler: event
 * listeners are synchronous (no queue, no worker threads). One {@code ListenerExecutor} is created
 * per registered listener instance at registration time (cold path). Its {@link
 * #toInvoker(HandlerInvoker)} builds the {@link HandlerInvoker} that {@link DefaultEventBus}
 * registers in the {@link net.nexus_flow.core.runtime.registry.HandlerRegistry}.
 *
 * <p>{@link DefaultEventBus} retains ownership of the global {@link DeadLetterQueue} and {@link
 * EventPublishBackpressureSettings}; the executor receives the DLQ via a {@link Supplier} so
 * runtime updates are reflected without re-registration.
 */
final class ListenerExecutor<E extends DomainEvent> {

    private static final Logger LOG = System.getLogger(ListenerExecutor.class.getName());

    private final DomainEventListener<E>                            listener;
    private final ListenerStats                                     stats;
    private final @Nullable Semaphore                               semaphore;
    private final @Nullable TokenBucket                             tokenBucket;
    private final @Nullable EventDeduplicator                       deduplicator;
    private final RetryPolicy                                       retryPolicy;
    private final @Nullable EventListenerErrorHandler<?>            errorHandler;
    private final Supplier<DeadLetterQueue>                         dlqSupplier;
    private final java.time.Clock                                   clock;
    private final net.nexus_flow.core.observability.MetricsRecorder metrics;
    private final net.nexus_flow.core.observability.TracingBridge   tracing;
    private final java.util.Map<String, String>                     tags;

    /** Volatile so pause/resume from any thread are immediately visible to the dispatch thread. */
    private volatile boolean paused;

    ListenerExecutor(DomainEventListener<E> listener, Supplier<DeadLetterQueue> dlqSupplier) {
        this(
             listener,
             dlqSupplier,
             java.time.Clock.systemUTC(),
             net.nexus_flow.core.observability.Observability.NO_OP);
    }

    ListenerExecutor(
            DomainEventListener<E> listener,
            Supplier<DeadLetterQueue> dlqSupplier,
            java.time.Clock clock) {
        this(listener, dlqSupplier, clock, net.nexus_flow.core.observability.Observability.NO_OP);
    }

    ListenerExecutor(
            DomainEventListener<E> listener,
            Supplier<DeadLetterQueue> dlqSupplier,
            java.time.Clock clock,
            net.nexus_flow.core.observability.Observability observability) {
        this.listener    = listener;
        this.dlqSupplier = dlqSupplier;
        this.clock       = java.util.Objects.requireNonNull(clock, "clock");
        java.util.Objects.requireNonNull(observability, "observability");
        this.metrics      = observability.metrics();
        this.tracing      = observability.tracing();
        this.tags         = java.util.Map.of("listener", listener.getClass().getName());
        this.stats        = new ListenerStats();
        this.retryPolicy  = listener.retryPolicy();
        this.errorHandler = listener.errorHandler();

        int concurrencyLevel = listener.concurrencyLevel();
        this.semaphore = concurrencyLevel > 0 ? new Semaphore(concurrencyLevel, true) : null;

        // SPI: prefer the adapter-provided TokenBucket / EventDeduplicator
        // over the in-process defaults built from rateLimit() / deduplicateEnabled().
        TokenBucket customBucket = listener.tokenBucket();
        if (customBucket != null) {
            this.tokenBucket = customBucket;
        } else {
            ListenerRateLimit rateLimit = listener.rateLimit();
            this.tokenBucket = rateLimit != null ? new InMemoryTokenBucket(rateLimit) : null;
        }

        EventDeduplicator customDedup = listener.deduplicator();
        if (customDedup != null) {
            this.deduplicator = customDedup;
        } else {
            this.deduplicator =
                    listener.deduplicateEnabled() ? new BoundedInMemoryEventDeduplicator() : null;
        }
    }

    void pause() {
        this.paused = true;
    }

    void resume() {
        this.paused = false;
    }

    boolean isPaused() {
        return paused;
    }

    ListenerStats stats() {
        return stats;
    }

    Class<?> listenerClass() {
        return listener.getClass();
    }

    /**
     * Builds a {@link HandlerInvoker} that applies all cross-cutting concerns around {@code base}.
     * Called once at registration time (cold path).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    HandlerInvoker<DomainEvent, Void> toInvoker(HandlerInvoker<DomainEvent, Void> base) {
        return new HandlerInvoker<>() {
            @Override
            // Propagates Throwable from base.invoke; throws clause retained to match HandlerInvoker
            // contract.
            public @Nullable Void invoke(DomainEvent event, ExecutionContext ctx) throws Throwable {
                if (paused) {
                    stats.recordFiltered();
                    return null;
                }

                stats.recordInvocation();
                metrics.incrementCounter("nexus.flow.listener.invocations", tags);

                if (tokenBucket != null && !tokenBucket.tryAcquire()) {
                    stats.recordRateLimited();
                    metrics.incrementCounter("nexus.flow.listener.rate_limited", tags);
                    return null;
                }

                // Skip the resolveIdempotencyKey call (which on AbstractDomainEvent computes
                // "aggregateId:sequenceNumber") entirely when no deduplicator is wired. Most listeners
                // do not opt into deduplication; without this guard every dispatch paid for the call
                // even when its result was discarded.
                String  idempotencyKey = (deduplicator != null) ? resolveIdempotencyKey(event) : null;
                boolean dedupClaimed   = tryClaimIdempotencyKey(idempotencyKey);
                if (deduplicator != null && idempotencyKey != null && !dedupClaimed) {
                    stats.recordDeduplicated();
                    metrics.incrementCounter("nexus.flow.listener.deduplicated", tags);
                    return null;
                }

                if (!((EventListener) listener).filter(event)) {
                    stats.recordFiltered();
                    metrics.incrementCounter("nexus.flow.listener.filtered", tags);
                    return null;
                }

                // Partition routing for PartitionedEventListener — see PartitionedEventListener for the
                // contract. Events whose key hashes to a different slot than this instance owns are
                // dropped silently (counted as filtered so per-shard metrics stay consistent).
                if (listener instanceof PartitionedEventListener<?>) {
                    @SuppressWarnings("unchecked") PartitionedEventListener<DomainEvent> partitioned    =
                            (PartitionedEventListener<DomainEvent>) listener;
                    int                                                                  partitionCount = partitioned.partitionCount();
                    if (partitionCount < 1) {
                        throw new IllegalStateException(
                                "PartitionedEventListener "
                                        + listener.getClass().getName()
                                        + " returned partitionCount="
                                        + partitionCount
                                        + "; must be >= 1");
                    }
                    String key = partitioned.partitionKey(event);
                    if (key == null) {
                        throw new IllegalStateException(
                                "PartitionedEventListener "
                                        + listener.getClass().getName()
                                        + " returned null partitionKey for event "
                                        + event.getClass().getName());
                    }
                    int target = Math.floorMod(key.hashCode(), partitionCount);
                    if (partitioned.partitionIndex() != target) {
                        stats.recordFiltered();
                        metrics.incrementCounter("nexus.flow.listener.partition_skipped", tags);
                        return null;
                    }
                }

                if (semaphore != null) {
                    try {
                        semaphore.acquire();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new FlowInterruptedException("Listener concurrency acquire interrupted", ie);
                    }
                }
                long startNanos = System.nanoTime();
                try (var span = tracing.startSpan("nexus.flow.listener.invoke", tags)) {
                    try {
                        return executeWithRetry(base, event, ctx, idempotencyKey, dedupClaimed);
                    } catch (Throwable t) {
                        span.recordException(t);
                        throw t;
                    } finally {
                        metrics.recordTimer(
                                            "nexus.flow.listener.duration",
                                            Duration.ofNanos(System.nanoTime() - startNanos),
                                            tags);
                        if (semaphore != null) {
                            semaphore.release();
                        }
                    }
                }
            }

            @Override
            public String handlerType() {
                return base.handlerType();
            }
        };
    }

    private static @Nullable String resolveIdempotencyKey(DomainEvent event) {
        try {
            return event.idempotencyKey();
        } catch (UnsupportedOperationException _) {
            return null;
        }
    }

    private boolean tryClaimIdempotencyKey(@Nullable String idempotencyKey) {
        if (deduplicator == null || idempotencyKey == null) {
            return true;
        }
        if (deduplicator instanceof BoundedInMemoryEventDeduplicator bounded) {
            return bounded.tryStartProcessing(idempotencyKey);
        }
        return !deduplicator.isDuplicate(idempotencyKey);
    }

    private void releaseIdempotencyKeyClaim(@Nullable String idempotencyKey) {
        if (deduplicator instanceof BoundedInMemoryEventDeduplicator bounded && idempotencyKey != null) {
            bounded.releaseProcessing(idempotencyKey);
        }
    }

    @SuppressWarnings({"unchecked"})
    private @Nullable Void executeWithRetry(
            HandlerInvoker<DomainEvent, Void> base,
            DomainEvent event,
            ExecutionContext ctx,
            @Nullable String idempotencyKey,
            boolean dedupClaimed) {

        boolean releaseDedupClaim = dedupClaimed;
        try {
            int       maxAttempts = retryPolicy.maxAttempts();
            Throwable lastFailure = null;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                // Poll cancellation/deadline BEFORE each invoke so the listener can't burn an
                // attempt when the caller has already cancelled. Retries with large delays
                // (exponential backoff at high attempts) would otherwise hold the dispatch
                // thread well past shutdown grace under a worker that already cancelled its token.
                // ctx is nullable on legacy dispatch paths (EventBus.dispatch(event) without context);
                // skipping the poll there preserves backwards compatibility.
                if (ctx != null) {
                    ctx.throwIfCancelledOrExpired();
                }
                try {
                    base.invoke(event, ctx);
                    if (deduplicator != null && idempotencyKey != null) {
                        deduplicator.markSeen(idempotencyKey);
                        releaseDedupClaim = false;
                    }
                    stats.recordSuccess();
                    metrics.incrementCounter("nexus.flow.listener.success", tags);
                    return null;
                } catch (Throwable t) {
                    lastFailure = t;
                    metrics.incrementCounter("nexus.flow.listener.retries", tags);
                    if (attempt < maxAttempts) {
                        Duration delay = retryPolicy.delayFor(attempt);
                        if (!delay.isZero()) {
                            try {
                                Thread.sleep(delay.toMillis(), delay.toNanosPart() % 1_000_000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                // The interrupt is the proximate cause (it stopped the retry
                                // sleep); the original listener failure that triggered the
                                // retry is preserved as a suppressed exception so tooling
                                // that unwraps `cause` to detect interrupts works correctly.
                                throw ThrowableUtils.withSuppressed(
                                                                    new FlowInterruptedException("Listener retry interrupted", ie), t);
                            }
                        }
                    }
                }
            }

            stats.recordError();
            metrics.incrementCounter("nexus.flow.listener.errors", tags);

            if (lastFailure == null) {
                // maxAttempts <= 0 — loop did not execute, no failure recorded.
                return null;
            }

            if (errorHandler != null) {
                try {
                    @SuppressWarnings("unchecked") EventListenerErrorHandler<DomainEvent> eh =
                            (EventListenerErrorHandler<DomainEvent>) errorHandler;
                    eh.onError(event, lastFailure);
                    return null;
                } catch (Throwable handlerEx) {
                    lastFailure = handlerEx;
                }
            }

            DeadLetterQueue dlq = dlqSupplier.get();
            if (dlq != null) {
                try {
                    dlq.enqueue(
                                new DeadLetterEntry(
                                        event, listener.getClass(), lastFailure, clock.instant(), maxAttempts));
                    stats.recordDeadLettered();
                    metrics.incrementCounter("nexus.flow.listener.dead_lettered", tags);
                    return null;
                } catch (Throwable dlqEx) {
                    LOG.log(
                            Level.WARNING,
                            "Dead-letter enqueue failed; propagating the original listener failure",
                            dlqEx);
                }
            }

            if (lastFailure instanceof RuntimeException re) {
                throw re;
            }
            throw new ListenerInvocationException(lastFailure);
        } finally {
            if (releaseDedupClaim) {
                releaseIdempotencyKeyClaim(idempotencyKey);
            }
        }
    }
}

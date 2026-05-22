package net.nexus_flow.core.saga;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Synchronous {@code awaitCompletion(timeout)} primitive for sagas. The audit identified
 * that callers wanting "fire a command, await the saga's terminal state, return the result"
 * had no built-in primitive — they had to roll their own poll loop. This awaiter does the
 * work uniformly, push when the storage supports it and polling otherwise.
 *
 * <h2>Push vs polling</h2>
 *
 * The awaiter subscribes to {@link SagaStorage#subscribe(SagaStorageObserver)} on
 * construction. When the storage natively supports push (in-memory direct notification,
 * JDBC LISTEN/NOTIFY, Redis pub-sub) every state change wakes the awaiter inline — zero
 * wasted reads. As a defence-in-depth fallback, a low-frequency poll (default 1 s) catches
 * any push the storage might have missed AND covers backends whose
 * {@link SagaStorage#subscribe} returns the
 * {@link SagaStorageObserver.Subscription#NO_OP} sentinel.
 *
 * <h2>Threading</h2>
 *
 * Push notifications arrive on whatever thread the storage uses to invoke observers — for
 * the in-memory backend that is the saver's thread. The awaiter's completion path is
 * lock-free (atomic state on the per-wait entry). Polling and timeout-expiry tasks run on
 * a single-threaded {@link ScheduledExecutorService} owned by the awaiter; close shuts it
 * down and cancels every in-flight wait.
 *
 * <h2>Why poll AND push</h2>
 *
 * Push is cheaper on the steady state but the framework cannot prove that every JDBC
 * backend's LISTEN delivers under every failure mode (connection loss, replica failover).
 * The low-frequency fallback poll closes the gap. In-memory storage rarely needs the poll
 * but the cost is negligible — one read per second per in-flight wait.
 */
public final class SagaCompletionAwaiter implements AutoCloseable {

    private final SagaStorage              storage;
    private final ScheduledExecutorService scheduler;
    private final boolean                  ownsScheduler;
    private final Duration                 pollInterval;

    private final ConcurrentHashMap<WaitKey, WaitEntry> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean                         closed   = new AtomicBoolean();
    private final SagaStorageObserver.Subscription      subscription;

    private SagaCompletionAwaiter(
            SagaStorage storage,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler,
            Duration pollInterval) {
        this.storage       = storage;
        this.scheduler     = scheduler;
        this.ownsScheduler = ownsScheduler;
        this.pollInterval  = pollInterval;
        this.subscription  = storage.subscribe(this::onPush);
    }

    /**
     * Await the terminal status of saga {@code (sagaType, correlationKey)}. Returns a
     * {@link CompletableFuture} that completes with the terminal {@link SagaState} on
     * success, or fails with {@link TimeoutException} when {@code timeout} elapses first.
     */
    public CompletableFuture<SagaState> awaitCompletion(
            String sagaType, String correlationKey, Duration timeout) {
        Objects.requireNonNull(sagaType, "sagaType");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        CompletableFuture<SagaState> future = new CompletableFuture<>();
        if (closed.get()) {
            future.completeExceptionally(new IllegalStateException("awaiter is closed"));
            return future;
        }
        WaitKey   key   = new WaitKey(sagaType, correlationKey);
        WaitEntry entry = new WaitEntry(future, timeout);
        inFlight.merge(key, entry, (existing, fresh) -> {
            // Multiple concurrent waits on the same saga share the entry — every completion
            // resolves all of them at once via the chained future. We keep the earliest entry
            // and chain the new future to it.
            existing.future.whenComplete((s, err) -> {
                if (err != null) {
                    fresh.future.completeExceptionally(err);
                } else {
                    fresh.future.complete(s);
                }
            });
            return existing;
        });
        // Fast-path: probe right away so an already-terminal saga returns synchronously.
        if (probeAndComplete(key)) {
            return future;
        }
        // Schedule defence-in-depth fallback polling + the timeout.
        WaitEntry tracked = inFlight.get(key);
        if (tracked != null) {
            tracked.pollTask.compareAndSet(null,
                                           scheduler.scheduleAtFixedRate(
                                                                         () -> probeAndComplete(key),
                                                                         pollInterval.toMillis(),
                                                                         pollInterval.toMillis(),
                                                                         TimeUnit.MILLISECONDS));
            tracked.timeoutTask.compareAndSet(null,
                                              scheduler.schedule(
                                                                 () -> failTimeout(key, timeout),
                                                                 timeout.toMillis(),
                                                                 TimeUnit.MILLISECONDS));
        }
        return future;
    }

    /** Number of in-flight awaits — diagnostics / tests. */
    public int inFlight() {
        return inFlight.size();
    }

    /**
     * Storage push hook. Probes whether the new state is terminal and, if so, completes
     * every pending future for that saga.
     */
    private void onPush(String sagaType, String correlationKey, SagaState newState) {
        if (closed.get()) {
            return;
        }
        if (!newState.status().isTerminal()) {
            return;
        }
        WaitEntry e = inFlight.remove(new WaitKey(sagaType, correlationKey));
        if (e == null) {
            return;
        }
        cancelHandles(e);
        e.future.complete(newState);
    }

    /**
     * Polling fallback. Reads the storage and resolves if the state is terminal. Returns
     * {@code true} when the entry was completed (and removed from the in-flight map).
     */
    private boolean probeAndComplete(WaitKey key) {
        if (closed.get()) {
            return false;
        }
        WaitEntry e = inFlight.get(key);
        if (e == null) {
            return false;
        }
        try {
            Optional<SagaState> state = storage.load(key.sagaType(), key.correlationKey());
            if (state.isPresent() && state.get().status().isTerminal()) {
                if (inFlight.remove(key, e)) {
                    cancelHandles(e);
                    e.future.complete(state.get());
                    return true;
                }
            }
        } catch (RuntimeException re) {
            if (inFlight.remove(key, e)) {
                cancelHandles(e);
                e.future.completeExceptionally(re);
            }
            return true;
        }
        return false;
    }

    private void failTimeout(WaitKey key, Duration timeout) {
        WaitEntry e = inFlight.remove(key);
        if (e == null) {
            return;
        }
        cancelHandles(e);
        e.future.completeExceptionally(
                                       new TimeoutException(
                                               "saga " + key.sagaType() + "/" + key.correlationKey()
                                                       + " did not reach a terminal state within " + timeout));
    }

    private static void cancelHandles(WaitEntry e) {
        cancelHandle(e.pollTask.get());
        cancelHandle(e.timeoutTask.get());
    }

    private static void cancelHandle(java.util.concurrent.@Nullable ScheduledFuture<?> h) {
        if (h != null) {
            h.cancel(false);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        subscription.close();
        for (var e : inFlight.values()) {
            cancelHandles(e);
            e.future.completeExceptionally(
                                           new java.util.concurrent.CancellationException("SagaCompletionAwaiter closed"));
        }
        inFlight.clear();
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private record WaitKey(String sagaType, String correlationKey) {
        WaitKey {
            Objects.requireNonNull(sagaType, "sagaType");
            Objects.requireNonNull(correlationKey, "correlationKey");
        }
    }

    /** One pending await — chained future, the fallback poll handle, and the timeout handle. */
    private static final class WaitEntry {
        final CompletableFuture<SagaState>                                              future;
        final Duration                                                                  timeout;
        final java.util.concurrent.atomic.AtomicReference<@Nullable ScheduledFuture<?>> pollTask    =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<@Nullable ScheduledFuture<?>> timeoutTask =
                new java.util.concurrent.atomic.AtomicReference<>();

        WaitEntry(CompletableFuture<SagaState> future, Duration timeout) {
            this.future  = future;
            this.timeout = timeout;
        }
    }

    /** Fluent builder. */
    public static Builder builder(SagaStorage storage) {
        return new Builder(storage);
    }

    public static final class Builder {
        private final SagaStorage        storage;
        private ScheduledExecutorService scheduler;
        private Duration                 pollInterval = Duration.ofSeconds(1);

        private Builder(SagaStorage storage) {
            this.storage = Objects.requireNonNull(storage, "storage");
        }

        /**
         * Override the fallback poll interval. Push notifications via
         * {@link SagaStorage#subscribe} are the primary completion signal; the poll is the
         * defence-in-depth fallback — keep it generous (default 1 s).
         */
        public Builder pollInterval(Duration interval) {
            Objects.requireNonNull(interval, "interval");
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("pollInterval must be positive: " + interval);
            }
            this.pollInterval = interval;
            return this;
        }

        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            return this;
        }

        public SagaCompletionAwaiter build() {
            ScheduledExecutorService effective;
            boolean                  owns;
            if (scheduler == null) {
                effective = Executors.newSingleThreadScheduledExecutor(r -> {
                              Thread t = new Thread(r, "nexus-saga-awaiter");
                              t.setDaemon(true);
                              return t;
                          });
                owns      = true;
            } else {
                effective = scheduler;
                owns      = false;
            }
            return new SagaCompletionAwaiter(storage, effective, owns, pollInterval);
        }
    }
}

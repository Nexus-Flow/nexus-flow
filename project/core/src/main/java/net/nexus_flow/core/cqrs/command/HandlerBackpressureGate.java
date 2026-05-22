package net.nexus_flow.core.cqrs.command;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ThrowableUtils;
import net.nexus_flow.core.runtime.dispatch.InvocationContext;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import org.jspecify.annotations.Nullable;

/**
 * back-pressure / saturation gate sitting in front of a {@link DefaultCommandHandlerExecutor}'s
 * task queue.
 *
 * <p>Responsibilities:
 *
 * <ul>
 * <li>Enforce the configured {@link HandlerBackpressureSettings#queueDepth() queue depth}.
 * <li>Apply the configured {@link SaturationPolicy} when the queue is full.
 * <li>Publish observability attributes ({@code handler.queueDepth}, {@code
 *       handler.queueOccupancy}, {@code handler.saturationPolicy}, {@code
 *       handler.rejections.count}, {@code queue.waitMs}) onto the current {@link
 * InvocationContext#attributes() invocation context attribute bag} when one is bound.
 * </ul>
 *
 * <p>Thread-safety: each gate is per-handler-per-runtime (* no-bleed); occupancy counters are
 * atomic; the rejection counter is a long.
 *
 * <p>Default behavior is no-op: when {@link HandlerBackpressureSettings#isDefault()} returns {@code
 * true}, {@link #beforeEnqueue} returns {@link Outcome#ENQUEUE} immediately without touching
 * counters, so the fast path is preserved byte-for-byte.
 */
final class HandlerBackpressureGate {

    private static final Logger LOGGER = System.getLogger(HandlerBackpressureGate.class.getName());

    private final HandlerBackpressureSettings              settings;
    private final long                                     cancelPollMs;
    private final @Nullable Class<?>                       commandType;
    private final int                                      concurrencyLevel;
    private final AtomicInteger                            occupancy     = new AtomicInteger();
    /**
     * Lock + condition variable that wake {@link SaturationPolicy#BLOCK_CALLER} waiters the
     * instant a slot is released by {@link #afterCompletion()} (or by the DROP_OLDEST eviction
     * counter decrement). Without this, the BLOCK_CALLER path would have to spin-sleep for
     * {@code cancelPollMs} between checks even when capacity has been freed mid-tick — adding
     * up to a full poll interval of unnecessary wait latency per dispatch. The wait still
     * times out on {@code cancelPollMs} so cooperative cancellation polling remains bounded.
     */
    private final java.util.concurrent.locks.ReentrantLock slotLock      =
            new java.util.concurrent.locks.ReentrantLock();
    private final java.util.concurrent.locks.Condition     slotAvailable = slotLock.newCondition();
    /**
     * Write-heavy rejection counter — every backpressure rejection increments. JMH validates
     * {@link java.util.concurrent.atomic.LongAdder} ≈ 11.5× faster than {@code AtomicLong}
     * under 8-thread contention (single-cell CAS vs striped cells). The {@code sum()} read
     * happens once per rejection to populate the metrics tag; the higher striped read cost is
     * still net positive because the write side dominates.
     */
    private final java.util.concurrent.atomic.LongAdder    rejections    = new java.util.concurrent.atomic.LongAdder();

    // commandType is reserved for diagnostics; future callers may pass non-null.
    //noinspection SameParameterValue
    HandlerBackpressureGate(
            HandlerBackpressureSettings settings, @Nullable Class<?> commandType, int concurrencyLevel) {
        this.settings         = Objects.requireNonNullElse(settings, HandlerBackpressureSettings.DEFAULTS);
        this.commandType      = commandType;
        this.concurrencyLevel = Math.max(0, concurrencyLevel);
        this.cancelPollMs     = Math.max(1L, this.settings.cancelPollInterval().toMillis());
    }

    HandlerBackpressureSettings settings() {
        return settings;
    }

    int occupancy() {
        return occupancy.get();
    }

    long rejectionCount() {
        return rejections.sum();
    }

    /**
     * Decide whether the dispatch can enter the executor's queue, and apply the configured policy
     * when it cannot.
     *
     * @param ctx      execution context of the dispatch (used for cooperative cancellation under
     *                 BLOCK_CALLER)
     * @param dropHead callback invoked by {@link SaturationPolicy#DROP_OLDEST} to evict the head of
     *                 the queue. Must return the evicted item (or {@code null} if the queue raced empty between
     *                 the check and the eviction).
     * @return the outcome the executor must apply
     */
    Outcome beforeEnqueue(ExecutionContext ctx, @Nullable DropHeadCallback dropHead) {
        if (settings.isDefault()) {
            // Fast path: behave exactly like. We skip
            // occupancy bookkeeping entirely so the unmodified
            // executor poll/re-enqueue paths cannot drift counters.
            // Observability attributes are still published when an
            // InvocationContext is bound so consumers see consistent
            // keys regardless of the policy in effect.
            publishEnqueueAttributes(-1, 0L);
            return Outcome.ENQUEUE;
        }
        int  depth     = effectiveCapacity();
        long waitStart = System.nanoTime();

        switch (settings.saturationPolicy()) {
            case DROP_NEWEST, REJECT -> {
                if (!tryReserveSlot(depth)) {
                    return rejectAndRecord(0L);
                }
                publishEnqueueAttributes(occupancy.get(), 0L);
                return Outcome.ENQUEUE;
            }
            case DROP_OLDEST         -> {
                while (!tryReserveSlot(depth)) {
                    Object dropped = dropHead != null ? dropHead.dropHead() : null;
                    if (dropped == null) {
                        // queue raced empty — try once more, then bail
                        if (tryReserveSlot(depth)) {
                            break;
                        }
                        // give up: surface as rejection to avoid livelock
                        return rejectAndRecord(0L);
                    }
                    // dropped item never gets a permit — release one
                    // logical occupancy slot so subsequent dispatches
                    // see the freed room. Signal so any BLOCK_CALLER waiter on a sibling
                    // dispatch wakes immediately instead of polling for the freed slot.
                    occupancy.decrementAndGet();
                    signalSlotAvailable();
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.log(
                                   Level.WARNING, "DROP_OLDEST: evicted head from queue of " + safeCommandName());
                    }
                }
                publishEnqueueAttributes(occupancy.get(), 0L);
                return Outcome.ENQUEUE;
            }
            case BLOCK_CALLER        -> {
                Duration timeout       = settings.blockTimeout();
                long     deadlineNanos =
                        timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
                // Notification-based wait: every afterCompletion()/dropHead release signals
                // slotAvailable, so the BLOCK_CALLER waiter wakes the instant capacity opens
                // instead of polling on a cancelPollMs grid. The condition.awaitNanos cap
                // doubles as the cancellation-poll budget — we wake at most once per
                // cancelPollMs to re-check ctx.throwIfCancelledOrExpired even if no signal
                // comes in (necessary because CancellationToken does not natively integrate
                // with Condition).
                long cancelPollNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(cancelPollMs);
                slotLock.lock();
                try {
                    while (!tryReserveSlot(depth)) {
                        //noinspection CaughtExceptionImmediatelyRethrown
                        try {
                            ctx.throwIfCancelledOrExpired();
                        } catch (FlowCancellationException | FlowDeadlineExceededException e) {
                            throw e;
                        }
                        long now = System.nanoTime();
                        if (timeout != null && now >= deadlineNanos) {
                            return rejectAndRecord(toMillis(now - waitStart));
                        }
                        long waitNanos = timeout == null ? cancelPollNanos : Math.min(cancelPollNanos, deadlineNanos - now);
                        if (waitNanos <= 0L) {
                            return rejectAndRecord(toMillis(System.nanoTime() - waitStart));
                        }
                        try {
                            slotAvailable.awaitNanos(waitNanos);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw ThrowableUtils.withSuppressed(
                                                                new FlowCancellationException("Interrupted while waiting for queue slot"),
                                                                ie);
                        }
                    }
                } finally {
                    slotLock.unlock();
                }
                long waitMs = toMillis(System.nanoTime() - waitStart);
                publishEnqueueAttributes(occupancy.get(), waitMs);
                return Outcome.ENQUEUE;
            }
        }
        // unreachable
        return Outcome.ENQUEUE;
    }

    /**
     * Called by the executor when a dispatch leaves the in-system accounting — i.e. when the handler
     * body has finished running (successfully or exceptionally), when a task was purged because its
     * context was canceled / expired before it ran, or when a task was evicted by {@link
     * SaturationPolicy#DROP_OLDEST} from outside the gate. Frees one logical capacity slot.
     */
    void afterCompletion() {
        if (settings.isDefault()) {
            return;
        }
        int current;
        do {
            current = occupancy.get();
            if (current == 0) {
                return;
            }
        } while (!occupancy.compareAndSet(current, current - 1));
        signalSlotAvailable();
    }

    /**
     * Wake one BLOCK_CALLER waiter (if any) when capacity has been freed. The lock acquire is
     * cheap on the uncontended common case and is the only correct way to signal a Condition.
     * Centralised here so every occupancy-decrement site can call it (afterCompletion + the
     * DROP_OLDEST eviction inside {@code beforeEnqueue}).
     */
    private void signalSlotAvailable() {
        slotLock.lock();
        try {
            slotAvailable.signal();
        } finally {
            slotLock.unlock();
        }
    }

    /**
     * Used by close() paths to stamp the count of items lost during shutdown. Returns the snapshot
     * value.
     */
    int snapshotOccupancy() {
        return occupancy.get();
    }

    /**
     * Effective in-system capacity: items allowed to be alive in the executor (queued + in-flight) at
     * any given moment. Equals {@code queueDepth + concurrencyLevel}, capped at {@link
     * Integer#MAX_VALUE}.
     */
    private int effectiveCapacity() {
        int qd = settings.queueDepth();
        if (qd == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        long sum = (long) qd + (long) concurrencyLevel;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private boolean tryReserveSlot(int depth) {
        int current;
        do {
            current = occupancy.get();
            if (current >= depth) {
                return false;
            }
        } while (!occupancy.compareAndSet(current, current + 1));
        return true;
    }

    // Always returns REJECT by design; method name documents the recording side effect.
    //noinspection SameReturnValue
    private Outcome rejectAndRecord(long waitMs) {
        rejections.increment();
        // sum() after increment is O(N-cells) but still beats AtomicLong.incrementAndGet at
        // the contention shape this gate sees on a saturated handler — see field Javadoc.
        publishRejectionAttributes(rejections.sum(), waitMs);
        return Outcome.REJECT;
    }

    private void publishEnqueueAttributes(int occupancyNow, long waitMs) {
        Optional<InvocationContext> inv = InvocationContext.current();
        if (inv.isEmpty()) {
            return;
        }
        var attrs = inv.get().attributes();
        attrs.put("handler.queueDepth", settings.queueDepth());
        attrs.put("handler.queueOccupancy", occupancyNow);
        attrs.put("handler.saturationPolicy", settings.saturationPolicy().name());
        attrs.put("queue.waitMs", waitMs);
    }

    private void publishRejectionAttributes(long total, long waitMs) {
        Optional<InvocationContext> inv = InvocationContext.current();
        if (inv.isEmpty()) {
            return;
        }
        var attrs = inv.get().attributes();
        attrs.put("handler.queueDepth", settings.queueDepth());
        attrs.put("handler.saturationPolicy", settings.saturationPolicy().name());
        attrs.put("handler.rejections.count", total);
        attrs.put("queue.waitMs", waitMs);
    }

    private String safeCommandName() {
        return commandType != null ? commandType.getSimpleName() : "<unknown>";
    }

    private static long toMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    /**
     * Construct the rejection exception that the executor must surface to the caller when this gate
     * returns {@link Outcome#REJECT}.
     */
    SaturationRejectedException buildRejection(ExecutionContext ctx) {
        return new SaturationRejectedException(
                commandType, settings.queueDepth(), settings.saturationPolicy(), ctx);
    }

    /** Sealed result of {@link #beforeEnqueue}. */
    enum Outcome {
        /** Caller may enqueue the dispatch as usual. */
        ENQUEUE,
        /** Caller must surface a {@link SaturationRejectedException}. */
        REJECT
    }

    /**
     * Callback contract for {@link SaturationPolicy#DROP_OLDEST}: the gate hands eviction back to the
     * executor since only the executor knows the concrete queue type. The callback returns the
     * evicted task (any non-null reference suffices) or {@code null} if the queue raced empty.
     */
    @FunctionalInterface
    interface DropHeadCallback {
        @Nullable Object dropHead();
    }

    // Convenience: bridge a Queue<?> into the DropHeadCallback shape.
    static DropHeadCallback evictHeadOf(Queue<?> queue) {
        return queue::poll;
    }
}

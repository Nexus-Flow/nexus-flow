package net.nexus_flow.core.cqrs.command;

import java.time.Duration;
import java.util.Objects;

/**
 * Mutable exponential-backoff strategy, thread-safe via {@code synchronized} methods.
 *
 * <p>Each {@link #nextWaitAndAdvance()} call returns the current wait then doubles it (capped at
 * {@link BackoffSettings#maxWaitMillis()}). {@link #reset()} atomically returns the strategy to its
 * base state. The synchronized block guards both internal fields ({@code waitMillis} + {@code
 * inBackoffState}) as one atomic group — there is no observable mixed state mid-transition.
 *
 * <p>{@link #isInBackoffState()} semantics preserve the legacy contract: the flag flips on the
 * SECOND consecutive advance (the first advance moves {@code waitMillis} from base to {@code 2 ×
 * base} but the flag stays {@code false}; the second advance reads the now-elevated wait and flips
 * the flag). This is equivalent to the pre-redesign {@code if (waitMillis > baseWaitMillis)
 * inBackoffState = true} guard.
 *
 * <p>Configuration is supplied via {@link BackoffSettings} at construction and immutable for the
 * strategy's lifetime; runtime tuning requires constructing a new strategy.
 */
final class ExponentialBackoffStrategy implements BackoffStrategy {

    private final long     baseWaitMillis;
    private final long     maxWaitMillis;
    private final Duration cancellationPoll;

    // Guarded by the intrinsic lock on `this`. Both fields transition atomically.
    private long    waitMillis;
    private boolean inBackoffState;

    /**
     * Construct a strategy from explicit settings.
     *
     * @param settings the back-off configuration; must not be {@code null}
     * @throws NullPointerException if {@code settings} is {@code null}
     */
    ExponentialBackoffStrategy(BackoffSettings settings) {
        Objects.requireNonNull(settings, "settings");
        this.baseWaitMillis   = settings.baseWaitMillis();
        this.maxWaitMillis    = settings.maxWaitMillis();
        this.cancellationPoll = settings.cancellationPoll();
        this.waitMillis       = baseWaitMillis;
        this.inBackoffState   = false;
    }

    /**
     * Test-friendly convenience constructor — equivalent to {@code new ExponentialBackoffStrategy(new
     * BackoffSettings(BackoffSettings.DEFAULT_BASE_WAIT_MILLIS, maxWaitMillis,
     * BackoffSettings.DEFAULT_CANCELLATION_POLL))}.
     *
     * @param maxWaitMillis upper bound on the exponential progression; must be {@code >= 1}
     */
    ExponentialBackoffStrategy(long maxWaitMillis) {
        this(
             new BackoffSettings(
                     BackoffSettings.DEFAULT_BASE_WAIT_MILLIS,
                     maxWaitMillis,
                     BackoffSettings.DEFAULT_CANCELLATION_POLL));
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Duration nextWaitAndAdvance() {
        long current = waitMillis;
        waitMillis = Math.min(waitMillis * 2L, maxWaitMillis);
        if (current > baseWaitMillis) {
            inBackoffState = true;
        }
        return Duration.ofMillis(current);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean isInBackoffState() {
        return inBackoffState;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void reset() {
        waitMillis     = baseWaitMillis;
        inBackoffState = false;
    }

    /** {@inheritDoc} */
    @Override
    public Duration cancellationPoll() {
        return cancellationPoll;
    }
}

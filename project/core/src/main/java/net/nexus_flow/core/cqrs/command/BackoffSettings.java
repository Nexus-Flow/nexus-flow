package net.nexus_flow.core.cqrs.command;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-handler back-off tuning knobs consumed by {@link BackoffStrategy} implementations.
 *
 * <p>Three settings:
 *
 * <ul>
 * <li>{@code baseWaitMillis} — the initial wait at the start of a backoff progression (and the
 * value {@link BackoffStrategy#reset()} returns to). Must be {@code >= 1}; default {@code 1}.
 * <li>{@code maxWaitMillis} — upper bound on the exponential progression. Must be {@code >=
 *       baseWaitMillis}; default {@code 1000}.
 * <li>{@code cancellationPoll} — drainer cancellation poll interval; an order of magnitude below
 * typical handler latencies (default 25 ms). Bounds the worst-case wait between two
 * cancellation / deadline checks inside the {@code *HandlerExecutor} drain loops.
 * </ul>
 *
 * <p>Wired in through {@link CommandSettings#backoff()} so individual handlers can override the
 * defaults via {@link CommandSettings.Builder#backoff(BackoffSettings)}. The runtime constructs one
 * {@link ExponentialBackoffStrategy} per handler executor at construction time, snapshotting the
 * settings — runtime mutation of the settings record after the executor is built has no effect
 * (records are immutable, executors are wired once).
 */
public record BackoffSettings(long baseWaitMillis, long maxWaitMillis, Duration cancellationPoll) {

    /** Default base wait at the start of a backoff progression (and after {@code reset()}). */
    public static final long DEFAULT_BASE_WAIT_MILLIS = 1L;

    /** Default upper bound on the exponential progression. */
    public static final long DEFAULT_MAX_WAIT_MILLIS = 1000L;

    /** Default drainer cancellation poll interval. */
    public static final Duration DEFAULT_CANCELLATION_POLL = Duration.ofMillis(25L);

    /** Sensible defaults: 1 ms base, 1000 ms max, 25 ms cancellation poll. */
    private static final BackoffSettings DEFAULTS =
            new BackoffSettings(
                    DEFAULT_BASE_WAIT_MILLIS, DEFAULT_MAX_WAIT_MILLIS, DEFAULT_CANCELLATION_POLL);

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException if {@code baseWaitMillis} is less than {@code 1}, if {@code
     *     maxWaitMillis}            is less than {@code baseWaitMillis}, or if {@code cancellationPoll} is zero
     *                                  or negative
     * @throws NullPointerException     if {@code cancellationPoll} is {@code null}
     */
    public BackoffSettings {
        if (baseWaitMillis < 1L) {
            throw new IllegalArgumentException(
                    "baseWaitMillis must be >= 1 (got " + baseWaitMillis + ")");
        }
        if (maxWaitMillis < baseWaitMillis) {
            throw new IllegalArgumentException(
                    "maxWaitMillis ("
                            + maxWaitMillis
                            + ") must be >= baseWaitMillis ("
                            + baseWaitMillis
                            + ")");
        }
        Objects.requireNonNull(cancellationPoll, "cancellationPoll");
        if (cancellationPoll.isZero() || cancellationPoll.isNegative()) {
            throw new IllegalArgumentException(
                    "cancellationPoll must be strictly positive (got " + cancellationPoll + ")");
        }
    }

    /**
     * @return {@link #DEFAULTS}.
     */
    public static BackoffSettings defaults() {
        return DEFAULTS;
    }
}

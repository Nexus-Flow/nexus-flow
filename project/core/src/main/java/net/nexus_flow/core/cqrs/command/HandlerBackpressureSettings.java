package net.nexus_flow.core.cqrs.command;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * per-handler back-pressure settings.
 *
 * <p>Carries the three knobs that govern how a handler executor reacts to queue saturation:
 *
 * <ul>
 * <li>{@code queueDepth} — maximum number of pending dispatches allowed in the executor's queue
 * at any given time. The default {@link Integer#MAX_VALUE} preserves semantics (unbounded
 * queue, no back-pressure).
 * <li>{@code saturationPolicy} — what to do when the queue is full; see {@link SaturationPolicy}.
 * Default {@link SaturationPolicy#BLOCK_CALLER}.
 * <li>{@code blockTimeout} — only relevant for {@link SaturationPolicy#BLOCK_CALLER}. When {@code
 *       null} (the default), the caller waits forever (subject to cooperative cancellation). When
 * non-null, the caller waits at most {@code blockTimeout} for a queue slot before surfacing a
 * {@link SaturationRejectedException}. Implementations poll cancellation roughly every {@link
 * BackoffStrategy#cancellationPoll()} millis so a cancellation observed mid-wait is honored
 * promptly.
 * </ul>
 *
 * <p>Defaults are explicitly chosen so the entire test suite (178 tests) and the demo binary stay
 * byte-identical: when a handler does not call {@code CommandSettings.Builder.backpressure} the
 * executor walks the unchanged code paths.
 */
public record HandlerBackpressureSettings(
                                          int queueDepth,
                                          SaturationPolicy saturationPolicy,
                                          @Nullable Duration blockTimeout,
                                          Duration cancelPollInterval) {

    /**
     * Default cancellation poll interval used by {@link SaturationPolicy#BLOCK_CALLER} waits. Matches
     * the {@link BackoffStrategy#cancellationPoll()} default so the framework presents a single "how
     * fast does cancellation propagate?" knob to operators.
     */
    public static final Duration DEFAULT_CANCEL_POLL_INTERVAL = Duration.ofMillis(25);

    /**
     * Documented default. Stable instance so identity-based caches (e.g. {@code cachedSettingsKey} in
     * the handler executors) treat unchanged settings as a cache hit.
     */
    public static final HandlerBackpressureSettings DEFAULTS =
            new HandlerBackpressureSettings(
                    Integer.MAX_VALUE, SaturationPolicy.BLOCK_CALLER, null, DEFAULT_CANCEL_POLL_INTERVAL);

    /**
     * Backwards-compatible 3-arg constructor — defaults {@code cancelPollInterval} to {@link
     * #DEFAULT_CANCEL_POLL_INTERVAL}.
     *
     * @param queueDepth       maximum pending dispatches; see record component docs
     * @param saturationPolicy what to do when full; see {@link SaturationPolicy}
     * @param blockTimeout     optional BLOCK_CALLER wait cap; {@code null} = wait forever
     */
    public HandlerBackpressureSettings(
            int queueDepth, SaturationPolicy saturationPolicy, @Nullable Duration blockTimeout) {
        this(queueDepth, saturationPolicy, blockTimeout, DEFAULT_CANCEL_POLL_INTERVAL);
    }

    /**
     * Creates validated back-pressure settings.
     *
     * @throws IllegalArgumentException if {@code queueDepth} is negative, {@code blockTimeout} is
     *                                  non-positive, or {@code cancelPollInterval} is non-positive
     * @throws NullPointerException     if {@code saturationPolicy} or {@code cancelPollInterval} is null
     */
    public HandlerBackpressureSettings {
        if (queueDepth < 0) {
            throw new IllegalArgumentException("queueDepth must be >= 0 (got " + queueDepth + ")");
        }
        Objects.requireNonNull(saturationPolicy, "saturationPolicy");
        if (blockTimeout != null && (blockTimeout.isNegative() || blockTimeout.isZero())) {
            throw new IllegalArgumentException(
                    "blockTimeout, if specified, must be strictly positive (got " + blockTimeout + ")");
        }
        Objects.requireNonNull(cancelPollInterval, "cancelPollInterval");
        if (cancelPollInterval.isNegative() || cancelPollInterval.isZero()) {
            throw new IllegalArgumentException(
                    "cancelPollInterval must be strictly positive (got " + cancelPollInterval + ")");
        }
    }

    /**
     * @return {@code true} when the settings are equivalent to {@link #DEFAULTS} — used by executors
     *         to take the no-op fast path.
     */
    public boolean isDefault() {
        return queueDepth == Integer.MAX_VALUE && saturationPolicy == SaturationPolicy.BLOCK_CALLER && blockTimeout == null && DEFAULT_CANCEL_POLL_INTERVAL
                .equals(cancelPollInterval);
    }
}

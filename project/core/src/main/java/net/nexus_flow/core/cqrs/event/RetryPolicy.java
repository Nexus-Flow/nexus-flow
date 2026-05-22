package net.nexus_flow.core.cqrs.event;

import java.time.Duration;

/**
 * Per-listener retry contract applied by {@link DefaultEventBus} when a listener's {@code handle()}
 * throws. The sealed hierarchy makes dispatch exhaustive (no default branch needed).
 *
 * <ul>
 * <li>{@link NoRetry} — propagates the first failure immediately (default).
 * <li>{@link FixedDelay} — retries up to {@code maxAttempts-1} additional times with a constant
 * {@code delay} between attempts.
 * <li>{@link Exponential} — retries with exponentially-growing delays capped at {@code maxDelay}.
 * </ul>
 *
 * <p>Retry sleep happens on the caller thread; keep delays short or use async execution modes.
 *
 * <p>
 *
 * {@snippet :
 * var listener = DomainEventListener.forEvent(OrderPlaced.class)
 *         .handle(event -> notifier.send(event))
 *         .withRetryPolicy(new RetryPolicy.FixedDelay(3, Duration.ofMillis(200)));
 * eventBus.register(listener);
 * }
 */
public sealed interface RetryPolicy
        permits RetryPolicy.NoRetry, RetryPolicy.FixedDelay, RetryPolicy.Exponential {

    /** Singleton: no retries (default listener policy). */
    NoRetry NO_RETRY = new NoRetry();

    // False positive: FixedDelay/Exponential records accept any maxAttempts value; current tests use
    // 1.
    //noinspection SameReturnValue
    /** Maximum number of total attempts (1 = no retry, 3 = 1 original + 2 retries). */
    int maxAttempts();

    /** Delay to wait before the given attempt number (1-based). */
    Duration delayFor(int attemptNumber);

    /** No retry — propagates the first failure immediately. */
    record NoRetry() implements RetryPolicy {
        /** {@inheritDoc} */
        @Override
        public int maxAttempts() {
            return 1;
        }

        /** {@inheritDoc} */
        @Override
        public Duration delayFor(int attemptNumber) {
            return Duration.ZERO;
        }
    }

    /** Retry with a fixed delay between every attempt. */
    record FixedDelay(int maxAttempts, Duration delay) implements RetryPolicy {
        /**
         * Creates a fixed-delay retry policy.
         *
         * @throws IllegalArgumentException if {@code maxAttempts < 1}
         * @throws NullPointerException     if {@code delay} is {@code null}
         */
        public FixedDelay {
            if (maxAttempts < 1)
                throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + maxAttempts);
            java.util.Objects.requireNonNull(delay, "delay");
        }

        /** {@inheritDoc} */
        @Override
        public Duration delayFor(int attemptNumber) {
            return delay;
        }
    }

    /** Retry with exponentially growing delays (base * 2^(attempt-1)), capped at maxDelay. */
    record Exponential(int maxAttempts, Duration baseDelay, Duration maxDelay)
            implements RetryPolicy {
        /**
         * Creates an exponential-backoff retry policy.
         *
         * @throws IllegalArgumentException if {@code maxAttempts < 1}
         * @throws NullPointerException     if {@code baseDelay} or {@code maxDelay} is {@code null}
         */
        public Exponential {
            if (maxAttempts < 1)
                throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + maxAttempts);
            java.util.Objects.requireNonNull(baseDelay, "baseDelay");
            java.util.Objects.requireNonNull(maxDelay, "maxDelay");
        }

        /** {@inheritDoc} */
        @Override
        public Duration delayFor(int attemptNumber) {
            // The shift cap of 62 is a JVM-bit-width overflow guard, NOT a tuning knob: shifts >= 64
            // are undefined for `long` (only the low 6 bits of the count are used), so 62 is the
            // largest safe exponent against a signed long. Tune effective retry timing via
            // `baseDelay` / `maxDelay`. A negative wrap-around result is also treated as saturated.
            int  shift = Math.min(attemptNumber - 1, 62);
            long nanos = baseDelay.toNanos() << shift;
            if (nanos < 0) {
                return maxDelay;
            }
            return Duration.ofNanos(Math.min(nanos, maxDelay.toNanos()));
        }
    }
}

package net.nexus_flow.core.cqrs.event;

/**
 * Per-listener rate-limit configuration enforced by {@link DefaultEventBus}. Uses a token-bucket
 * algorithm: up to {@code burst} events may be processed immediately; thereafter the rate is capped
 * at {@code maxPerSecond}.
 *
 * <p>
 *
 * {@snippet :
 * &#64;Override
 * public ListenerRateLimit rateLimit() {
 *     return new ListenerRateLimit(100, 200); // 100/s sustained, burst of 200
 * }
 * }
 *
 * @param maxPerSecond the sustained refill rate in tokens per second
 * @param burst        the maximum number of tokens that may accumulate for bursts
 */
public record ListenerRateLimit(int maxPerSecond, int burst) {

    /**
     * Creates a validated rate-limit configuration.
     *
     * @throws IllegalArgumentException if {@code maxPerSecond < 1} or {@code burst < maxPerSecond}
     */
    public ListenerRateLimit {
        if (maxPerSecond < 1)
            throw new IllegalArgumentException("maxPerSecond must be >= 1, got: " + maxPerSecond);
        if (burst < maxPerSecond)
            throw new IllegalArgumentException("burst must be >= maxPerSecond");
    }

    /**
     * Convenience factory where burst equals the sustained rate.
     *
     * @param maxPerSecond the sustained rate and burst size
     * @return a rate limit with no extra burst allowance beyond the sustained rate
     */
    public static ListenerRateLimit of(int maxPerSecond) {
        return new ListenerRateLimit(maxPerSecond, maxPerSecond);
    }
}

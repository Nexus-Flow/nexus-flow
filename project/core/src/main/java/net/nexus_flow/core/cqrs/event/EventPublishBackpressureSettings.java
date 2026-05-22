package net.nexus_flow.core.cqrs.event;

import java.util.Objects;

/**
 * Controls how many concurrent {@link EventBus#dispatchResult} calls are allowed and what happens
 * when the limit is reached.
 *
 * <p>Meaningful when multiple threads are publishing events concurrently (e.g. a saga emitting
 * events while another command handler is also publishing). Without a limit, all callers proceed;
 * with a limit the bus can back-pressure or shed load.
 *
 * <p>
 *
 * {@snippet :
 * var settings = EventPublishBackpressureSettings.of(16, EventPublishSaturationPolicy.REJECT);
 * var runtime = FlowRuntime.builder()
 *         .eventPublishBackpressure(settings)
 *         .build();
 * }
 *
 * @param maxConcurrentDispatches the maximum number of in-flight dispatch calls allowed
 * @param policy                  the saturation policy to apply when the limit is reached
 */
public record EventPublishBackpressureSettings(
                                               int maxConcurrentDispatches, EventPublishSaturationPolicy policy) {

    /** Sentinel: no limit on concurrent dispatches. */
    public static final EventPublishBackpressureSettings UNLIMITED =
            new EventPublishBackpressureSettings(
                    Integer.MAX_VALUE, EventPublishSaturationPolicy.BLOCK_CALLER);

    /**
     * Creates validated backpressure settings.
     *
     * @throws IllegalArgumentException if {@code maxConcurrentDispatches < 1}
     * @throws NullPointerException     if {@code policy} is {@code null}
     */
    public EventPublishBackpressureSettings {
        if (maxConcurrentDispatches < 1)
            throw new IllegalArgumentException(
                    "maxConcurrentDispatches must be >= 1, got: " + maxConcurrentDispatches);
        Objects.requireNonNull(policy, "policy");
    }

    /**
     * Factory for validated backpressure settings.
     *
     * @param maxConcurrentDispatches the maximum number of concurrent dispatches to allow
     * @param policy                  the saturation policy to apply once the limit is reached
     * @return a validated settings record
     */
    public static EventPublishBackpressureSettings of(
            int maxConcurrentDispatches, EventPublishSaturationPolicy policy) {
        return new EventPublishBackpressureSettings(maxConcurrentDispatches, policy);
    }

    /**
     * Returns whether this configuration represents the unlimited sentinel.
     *
     * @return {@code true} when dispatches are effectively unbounded
     */
    public boolean isUnlimited() {
        return maxConcurrentDispatches == Integer.MAX_VALUE;
    }
}

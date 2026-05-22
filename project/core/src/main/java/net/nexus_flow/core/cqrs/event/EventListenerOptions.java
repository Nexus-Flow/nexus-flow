package net.nexus_flow.core.cqrs.event;

import java.util.Objects;
import net.nexus_flow.core.ddd.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Configuration captured by event listeners created by framework adapters.
 *
 * <p>Every component is public and value-based so Spring, Quarkus or Micronaut integrations can map
 * annotation attributes / configuration properties into a listener without subclassing {@link
 * AbstractDomainEventListener}.
 *
 * @param order              the listener ordering hint; lower values run first
 * @param parallelSafe       whether the listener may participate in parallel fan-out for one event
 * @param retryPolicy        the retry policy to apply when {@code handle} throws
 * @param errorHandler       optional terminal error callback invoked after retries are exhausted
 * @param concurrencyLevel   the maximum concurrent invocations allowed for this listener instance
 * @param deduplicateEnabled whether to deduplicate by {@link DomainEvent#idempotencyKey()}
 * @param rateLimit          optional per-listener token-bucket rate limit
 */
public record EventListenerOptions<E extends DomainEvent>(
                                                          int order,
                                                          boolean parallelSafe,
                                                          RetryPolicy retryPolicy,
                                                          @Nullable EventListenerErrorHandler<E> errorHandler,
                                                          int concurrencyLevel,
                                                          boolean deduplicateEnabled,
                                                          @Nullable ListenerRateLimit rateLimit) {

    private static final EventListenerOptions<DomainEvent> DEFAULTS =
            new EventListenerOptions<>(0, false, RetryPolicy.NO_RETRY, null, 0, false, null);

    /**
     * Creates validated listener options.
     *
     * @throws NullPointerException     if {@code retryPolicy} is {@code null}
     * @throws IllegalArgumentException if {@code concurrencyLevel < 0}
     */
    public EventListenerOptions {
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        if (concurrencyLevel < 0) {
            throw new IllegalArgumentException("concurrencyLevel must be >= 0, got: " + concurrencyLevel);
        }
    }

    /**
     * Returns the framework default listener options.
     *
     * @return the immutable default option set
     */
    public static EventListenerOptions<DomainEvent> defaults() {
        return DEFAULTS;
    }
}

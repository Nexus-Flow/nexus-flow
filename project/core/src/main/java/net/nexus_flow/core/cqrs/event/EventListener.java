package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;
import org.jspecify.annotations.Nullable;

sealed interface EventListener<E extends DomainEvent, H extends EventListener<E, H>>
        permits DomainEventListener {

    // SAM-like contract; implementations are expected to throw checked exceptions.
    @SuppressWarnings({"RedundantThrows", "NullableProblems"
    }) // parameter E is non-null by @NullMarked; IntelliJ false-positive on generic param
    void handle(E event) throws Exception;

    default int order() {
        return 0;
    }

    /**
     * opt-in declaration that this listener is safe to invoke concurrently with peer listeners of the
     * SAME event instance.
     *
     * <p>Default is {@code false} — the bus keeps sequential-in-registration-order dispatch unless
     * every listener of a given event opts in.
     */
    default boolean parallelSafe() {
        return false;
    }

    /**
     * Optional filter predicate evaluated per-event BEFORE {@link #handle} is called. Return {@code
     * false} to skip this event silently (no retry, no error handler).
     *
     * <p>Default: accept all events.
     *
     * <p>
     *
     * {@snippet :
     * &#64;Override
     * public boolean filter(OrderPlaced event) {
     *     return event.amount().compareTo(BigDecimal.valueOf(100)) > 0;
     * }
     * }
     */
    default boolean filter(E event) {
        return true;
    }

    /**
     * Per-listener retry policy applied when {@link #handle} throws. Retries are exhausted before
     * {@link #errorHandler()} is invoked.
     *
     * <p>Default: {@link RetryPolicy#NO_RETRY} — no retries.
     */
    default RetryPolicy retryPolicy() {
        return RetryPolicy.NO_RETRY;
    }

    /**
     * Last-resort error callback invoked after all {@link #retryPolicy()} attempts are exhausted.
     *
     * <p>If the handler returns normally, the failure is considered handled and fan-out continues. If
     * it throws, the exception propagates to the bus-level {@code ErrorPolicy}.
     *
     * <p>Default: {@code null} — propagate the exception directly to the bus.
     */
    default @Nullable EventListenerErrorHandler<E> errorHandler() {
        return null;
    }

    /**
     * Maximum number of concurrent invocations of this listener instance. Enforced by a {@link
     * java.util.concurrent.Semaphore} per listener in {@link DefaultEventBus}.
     *
     * <p>{@code 0} (default) means unlimited — the bus does not acquire a permit before invoking.
     * Meaningful primarily for parallel fan-out ({@link #parallelSafe()} = true) or when multiple
     * threads are dispatching events of the same type concurrently.
     */
    default int concurrencyLevel() {
        return 0;
    }

    /**
     * Whether this listener should deduplicate events by {@link
     * net.nexus_flow.core.ddd.DomainEvent#idempotencyKey()}.
     *
     * <p>When {@code true}, the bus checks the configured {@link EventDeduplicator} before invoking
     * {@link #handle}. If the event is a duplicate, it is skipped and counted in {@link
     * ListenerStats#deduplicated()}.
     *
     * <p>Default: {@code false}. Enable on listeners that must be exactly-once-effective (e.g.
     * external notification senders, payment processors).
     */
    default boolean deduplicateEnabled() {
        return false;
    }

    /**
     * Optional per-listener rate limit. When non-{@code null}, the bus enforces the limit via a
     * token-bucket algorithm ({@link InMemoryTokenBucket}). Events that cannot acquire a token are
     * dropped and counted in {@link ListenerStats#rateLimited()}.
     *
     * <p>Default: {@code null} — no rate limit.
     */
    default @Nullable ListenerRateLimit rateLimit() {
        return null;
    }

    /**
     * SPI hook — return a custom {@link TokenBucket} to override the in-memory default that {@link
     * DefaultEventBus} would build from {@link #rateLimit()}.
     *
     * <p>Returning a non-{@code null} value short-circuits the {@link #rateLimit()} -based default
     * and lets framework-integration modules plug in distributed rate limiting (e.g. Bucket4j on top
     * of Lettuce/Jedis) without touching {@code core}.
     *
     * <p>Default: {@code null} — fall back to the {@link #rateLimit()} configuration record.
     */
    default @Nullable TokenBucket tokenBucket() {
        return null;
    }

    /**
     * SPI hook — return a custom {@link EventDeduplicator} to override the bounded in-memory default
     * that {@link DefaultEventBus} would build when {@link #deduplicateEnabled()} is {@code true}.
     *
     * <p>Returning a non-{@code null} value short-circuits the {@link #deduplicateEnabled()} -based
     * default and lets framework-integration modules plug in distributed deduplication (e.g. a Redis
     * {@code SET NX EX} adapter or a JDBC-unique-constraint adapter) without touching {@code core}.
     *
     * <p>Default: {@code null} — fall back to the {@link #deduplicateEnabled()} boolean flag.
     */
    default @Nullable EventDeduplicator deduplicator() {
        return null;
    }
}

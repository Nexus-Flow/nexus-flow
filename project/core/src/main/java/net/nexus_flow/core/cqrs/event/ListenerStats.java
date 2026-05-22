package net.nexus_flow.core.cqrs.event;

import java.util.concurrent.atomic.LongAdder;

/**
 * Per-listener runtime statistics collected by {@link DefaultEventBus}. All counters are
 * monotonically increasing and thread-safe.
 *
 * <p>Counters use {@link LongAdder} rather than {@link java.util.concurrent.atomic.AtomicLong}
 * because the write path is hot (one increment per dispatch + outcome) and the read path
 * (observability scrape) is rare. {@code LongAdder} striped-cell design avoids the cache-line
 * ping-pong of CAS contention on a single counter; reads {@code sum()} are slightly more
 * expensive but observed only by metrics consumers.
 */
public final class ListenerStats {

    private final LongAdder invocations  = new LongAdder();
    private final LongAdder successes    = new LongAdder();
    private final LongAdder errors       = new LongAdder();
    private final LongAdder filtered     = new LongAdder();
    private final LongAdder deduplicated = new LongAdder();
    private final LongAdder rateLimited  = new LongAdder();
    private final LongAdder deadLettered = new LongAdder();

    void recordInvocation() {
        invocations.increment();
    }

    void recordSuccess() {
        successes.increment();
    }

    void recordError() {
        errors.increment();
    }

    void recordFiltered() {
        filtered.increment();
    }

    void recordDeduplicated() {
        deduplicated.increment();
    }

    void recordRateLimited() {
        rateLimited.increment();
    }

    void recordDeadLettered() {
        deadLettered.increment();
    }

    ListenerStats copy() {
        ListenerStats copy = new ListenerStats();
        copy.invocations.add(invocations());
        copy.successes.add(successes());
        copy.errors.add(errors());
        copy.filtered.add(filtered());
        copy.deduplicated.add(deduplicated());
        copy.rateLimited.add(rateLimited());
        copy.deadLettered.add(deadLettered());
        return copy;
    }

    ListenerStats add(ListenerStats other) {
        invocations.add(other.invocations());
        successes.add(other.successes());
        errors.add(other.errors());
        filtered.add(other.filtered());
        deduplicated.add(other.deduplicated());
        rateLimited.add(other.rateLimited());
        deadLettered.add(other.deadLettered());
        return this;
    }

    /**
     * Returns how many times the listener was entered.
     *
     * @return the total invocation count
     */
    public long invocations() {
        return invocations.sum();
    }

    /**
     * Returns how many invocations completed successfully.
     *
     * @return the successful invocation count
     */
    public long successes() {
        return successes.sum();
    }

    /**
     * Returns how many invocations exhausted retries and were treated as errors.
     *
     * @return the error count
     */
    public long errors() {
        return errors.sum();
    }

    /**
     * Returns how many events were skipped by pause or filter checks.
     *
     * @return the filtered-event count
     */
    public long filtered() {
        return filtered.sum();
    }

    /**
     * Returns how many events were skipped as duplicates.
     *
     * @return the deduplicated-event count
     */
    public long deduplicated() {
        return deduplicated.sum();
    }

    /**
     * Returns how many events were dropped by the listener's rate limiter.
     *
     * @return the rate-limited count
     */
    public long rateLimited() {
        return rateLimited.sum();
    }

    /**
     * Returns how many failed events were routed to the dead-letter queue.
     *
     * @return the dead-lettered count
     */
    public long deadLettered() {
        return deadLettered.sum();
    }

    /**
     * Returns a human-readable summary of the current counter values.
     *
     * @return the current counters formatted for logs and diagnostics
     */
    @Override
    public String toString() {
        return "ListenerStats{invocations="
                + invocations()
                + ", successes="
                + successes()
                + ", errors="
                + errors()
                + ", filtered="
                + filtered()
                + ", deduplicated="
                + deduplicated()
                + ", rateLimited="
                + rateLimited()
                + ", deadLettered="
                + deadLettered()
                + "}";
    }
}

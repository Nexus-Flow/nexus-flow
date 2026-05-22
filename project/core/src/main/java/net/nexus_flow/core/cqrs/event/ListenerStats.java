package net.nexus_flow.core.cqrs.event;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-listener runtime statistics collected by {@link DefaultEventBus}. All counters are
 * monotonically increasing and thread-safe.
 */
public final class ListenerStats {

    private final AtomicLong invocations  = new AtomicLong();
    private final AtomicLong successes    = new AtomicLong();
    private final AtomicLong errors       = new AtomicLong();
    private final AtomicLong filtered     = new AtomicLong();
    private final AtomicLong deduplicated = new AtomicLong();
    private final AtomicLong rateLimited  = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();

    void recordInvocation() {
        invocations.incrementAndGet();
    }

    void recordSuccess() {
        successes.incrementAndGet();
    }

    void recordError() {
        errors.incrementAndGet();
    }

    void recordFiltered() {
        filtered.incrementAndGet();
    }

    void recordDeduplicated() {
        deduplicated.incrementAndGet();
    }

    void recordRateLimited() {
        rateLimited.incrementAndGet();
    }

    void recordDeadLettered() {
        deadLettered.incrementAndGet();
    }

    ListenerStats copy() {
        ListenerStats copy = new ListenerStats();
        copy.invocations.addAndGet(invocations());
        copy.successes.addAndGet(successes());
        copy.errors.addAndGet(errors());
        copy.filtered.addAndGet(filtered());
        copy.deduplicated.addAndGet(deduplicated());
        copy.rateLimited.addAndGet(rateLimited());
        copy.deadLettered.addAndGet(deadLettered());
        return copy;
    }

    ListenerStats add(ListenerStats other) {
        invocations.addAndGet(other.invocations());
        successes.addAndGet(other.successes());
        errors.addAndGet(other.errors());
        filtered.addAndGet(other.filtered());
        deduplicated.addAndGet(other.deduplicated());
        rateLimited.addAndGet(other.rateLimited());
        deadLettered.addAndGet(other.deadLettered());
        return this;
    }

    /**
     * Returns how many times the listener was entered.
     *
     * @return the total invocation count
     */
    public long invocations() {
        return invocations.get();
    }

    /**
     * Returns how many invocations completed successfully.
     *
     * @return the successful invocation count
     */
    public long successes() {
        return successes.get();
    }

    /**
     * Returns how many invocations exhausted retries and were treated as errors.
     *
     * @return the error count
     */
    public long errors() {
        return errors.get();
    }

    /**
     * Returns how many events were skipped by pause or filter checks.
     *
     * @return the filtered-event count
     */
    public long filtered() {
        return filtered.get();
    }

    /**
     * Returns how many events were skipped as duplicates.
     *
     * @return the deduplicated-event count
     */
    public long deduplicated() {
        return deduplicated.get();
    }

    /**
     * Returns how many events were dropped by the listener's rate limiter.
     *
     * @return the rate-limited count
     */
    public long rateLimited() {
        return rateLimited.get();
    }

    /**
     * Returns how many failed events were routed to the dead-letter queue.
     *
     * @return the dead-lettered count
     */
    public long deadLettered() {
        return deadLettered.get();
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

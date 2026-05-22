package net.nexus_flow.core.cqrs.command;

import java.io.Serial;

/**
 * Signals that the post-handler event drain loop exceeded its bounded depth — i.e. listeners
 * kept emitting new domain events into the active {@link
 * net.nexus_flow.core.cqrs.event.DomainEventContext} faster than they could be quiesced, beyond
 * the operator-configured {@code eventDrainMaxDepth}. Two well-known patterns trigger this:
 *
 * <ol>
 * <li>An accidental cycle — listener A emits {@code X}, listener B observes {@code X} and
 * emits {@code Y}, listener A observes {@code Y} and emits {@code X} again. The depth
 * counter eventually catches the loop instead of letting the JVM stall.
 * <li>A pathological fan-out — a saga reacting to one event records a long chain of
 * compensating events on the same dispatch. The default depth (32) covers realistic
 * saga chains; deployments with intentionally deeper chains raise the limit via
 * {@link net.nexus_flow.core.runtime.FlowRuntime.Builder#eventDrainMaxDepth(int)}.
 * </ol>
 *
 * <p>Events appended to the outbox up to the failing iteration are persisted (the outbox append
 * happens before the dispatch that may add more events), so manual replay through the
 * {@link net.nexus_flow.core.outbox.OutboxWorker} can recover delivery for everything written
 * up to the point of overflow. The last drained batch's <em>dispatch</em> never ran; the
 * exception is surfaced to the calling executor as a {@code FlowError.Technical} via the
 * standard handler-failure path.
 */
public final class EventDrainOverflowException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int depthReached;
    private final int maxDepth;

    /**
     * @param depthReached the iteration count at which the loop refused to continue (always
     *                     equals {@code maxDepth + 1} on overflow)
     * @param maxDepth     the configured ceiling that was exceeded
     */
    public EventDrainOverflowException(int depthReached, int maxDepth) {
        super(
              "post-handler event drain exceeded max depth "
                      + maxDepth
                      + " (reached "
                      + depthReached
                      + "). A listener is emitting events that themselves trigger listeners in"
                      + " an unbounded chain; raise FlowRuntime.Builder.eventDrainMaxDepth or"
                      + " break the cycle in the offending listener.");
        this.depthReached = depthReached;
        this.maxDepth     = maxDepth;
    }

    /** @return the iteration count at which the loop refused to continue. */
    public int depthReached() {
        return depthReached;
    }

    /** @return the configured ceiling that was exceeded. */
    public int maxDepth() {
        return maxDepth;
    }
}

package net.nexus_flow.core.cqrs.event.exceptions;

/**
 * Thrown when {@link net.nexus_flow.core.cqrs.event.EventPublishSaturationPolicy#REJECT} is
 * configured and the {@link net.nexus_flow.core.cqrs.event.EventBus} dispatch slot limit has been
 * reached.
 */
public final class EventPublishRejectedException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception describing the rejected publish attempt.
     *
     * @param maxConcurrentDispatches the configured concurrent-dispatch limit that was already full
     */
    public EventPublishRejectedException(int maxConcurrentDispatches) {
        // Stack-traceless — backpressure rejection hot path under saturation. The actionable
        // info (configured limit) is in the message. Suppression chain remains active so
        // ThrowableUtils.withSuppressed and any addSuppressed callers keep working. Saves
        // ~200 ns per rejection.
        super(
              "EventBus dispatch rejected: maxConcurrentDispatches="
                      + maxConcurrentDispatches
                      + " already in flight. Configure EventPublishBackpressureSettings.",
              /* cause= */ null,
              /* enableSuppression= */ true,
              /* writableStackTrace= */ false);
    }
}

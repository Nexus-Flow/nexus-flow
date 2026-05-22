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
        super(
              "EventBus dispatch rejected: maxConcurrentDispatches="
                      + maxConcurrentDispatches
                      + " already in flight. Configure EventPublishBackpressureSettings.");
    }
}

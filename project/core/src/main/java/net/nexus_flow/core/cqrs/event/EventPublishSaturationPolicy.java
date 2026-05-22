package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.cqrs.event.exceptions.EventPublishRejectedException;

/**
 * What to do when an {@link EventBus} publish call arrives but the bus is already handling {@link
 * EventPublishBackpressureSettings#maxConcurrentDispatches()} concurrent dispatches.
 */
public enum EventPublishSaturationPolicy {
    /**
     * Block the caller thread until a dispatch slot is available. Cooperative — honors thread
     * interruption.
     */
    BLOCK_CALLER,
    /**
     * Silently drop the event (return an empty success result without invoking any listener). Useful
     * for metrics or audit events that can be safely skipped under load.
     */
    DROP,
    /** Reject the dispatch immediately with a {@link EventPublishRejectedException}. */
    REJECT
}

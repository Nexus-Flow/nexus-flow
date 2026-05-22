package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;

/**
 * Per-listener error callback invoked by {@link DefaultEventBus} after all {@link RetryPolicy}
 * attempts have been exhausted for a single listener invocation.
 *
 * <p>Implementations can:
 *
 * <ul>
 * <li><b>swallow</b> — return normally to signal "error handled; continue fan-out".
 * <li><b>rethrow</b> — rethrow or wrap the exception to propagate it up to the bus-level {@link
 * net.nexus_flow.core.runtime.ErrorPolicy}.
 * </ul>
 *
 * {@snippet :
 * DomainEventListener.forEvent(OrderPlaced.class)
 *         .handle(event -> mailer.send(event))
 *         .withErrorHandler((event, ex) -> LOG.warning("mailer failed: " + ex.getMessage()));
 * }
 */
@FunctionalInterface
public interface EventListenerErrorHandler<E extends DomainEvent> {

    /**
     * Called when the listener has exhausted all retry attempts.
     *
     * @param event the event being dispatched
     * @param cause the exception from the last attempt
     * @throws Throwable if the implementation wants to propagate the failure
     */
    // SAM-like contract; implementations are expected to throw checked exceptions or rethrow.
    @SuppressWarnings("RedundantThrows")
    void onError(E event, Throwable cause) throws Throwable;
}

package net.nexus_flow.advanced;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;

/**
 * Listens to {@link StockReservationFailedEvent} and triggers compensation: marks the order
 * cancelled and emits an {@link OrderCancelledEvent} downstream.
 *
 * <p>The runtime's recursive event drain catches the compensation event recorded on the
 * aggregate and appends it to the outbox automatically — no manual drain plumbing in the
 * listener.
 */
public final class CompensationService
        extends AbstractDomainEventListener<StockReservationFailedEvent> {

    private static final Logger LOG = System.getLogger(CompensationService.class.getName());

    private final Map<String, OrderAggregate> orders;

    public CompensationService(Map<String, OrderAggregate> orders) {
        this.orders = orders;
    }

    @Override
    public void handle(StockReservationFailedEvent event) {
        OrderAggregate agg = orders.get(event.getAggregateId());
        if (agg == null) {
            return;
        }
        agg.cancel("compensation: " + event.reason());
        LOG.log(Level.WARNING,
                "[COMPENSATE] cancelling order={0} reason={1}",
                event.getAggregateId(), event.reason());
    }
}

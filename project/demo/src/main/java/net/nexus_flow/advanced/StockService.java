package net.nexus_flow.advanced;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;

/**
 * Listens to {@link OrderPlacedEvent}, attempts a stock reservation, and emits either
 * {@link StockReservedEvent} or {@link StockReservationFailedEvent} accordingly. Demonstrates
 * the "event triggers next saga step" pattern without needing a dedicated command bus call.
 *
 * <p>The runtime's recursive event drain takes care of the stock event the listener emits —
 * no manual outbox plumbing.
 */
public final class StockService extends AbstractDomainEventListener<OrderPlacedEvent> {

    private static final Logger LOG = System.getLogger(StockService.class.getName());

    private final Map<String, OrderAggregate> orders;
    private final AtomicInteger               triesUntilFirstFailure;

    public StockService(Map<String, OrderAggregate> orders, int triesBeforeFirstFailure) {
        this.orders                 = orders;
        this.triesUntilFirstFailure = new AtomicInteger(triesBeforeFirstFailure);
    }

    @Override
    public void handle(OrderPlacedEvent event) {
        OrderAggregate agg = orders.get(event.getAggregateId());
        if (agg == null) {
            return;
        }
        int remaining = triesUntilFirstFailure.decrementAndGet();
        if (remaining < 0 && event.quantity() > 100) {
            agg.markStockFailed("requested quantity exceeds available stock");
            LOG.log(Level.WARNING, "[STOCK] reservation FAILED order={0} qty={1}",
                    event.getAggregateId(), event.quantity());
        } else {
            agg.markStockReserved();
            LOG.log(Level.INFO, "[STOCK] reserved order={0} qty={1}",
                    event.getAggregateId(), event.quantity());
        }
    }
}

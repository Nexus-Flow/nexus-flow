package net.nexus_flow.advanced;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;

/**
 * Listens to {@link StockReservedEvent} and charges the payment. Emits
 * {@link PaymentChargedEvent} downstream.
 *
 * <p>The runtime's recursive event drain catches the listener-emitted event and routes it
 * through the outbox automatically — no manual {@code OutboxAppender.appendDrainedEvents}
 * needed.
 */
public final class PaymentService extends AbstractDomainEventListener<StockReservedEvent> {

    private static final Logger LOG = System.getLogger(PaymentService.class.getName());

    private final Map<String, OrderAggregate> orders;

    public PaymentService(Map<String, OrderAggregate> orders) {
        this.orders = orders;
    }

    @Override
    public void handle(StockReservedEvent event) {
        OrderAggregate agg = orders.get(event.getAggregateId());
        if (agg == null) {
            return;
        }
        agg.markPaymentCharged();
        LOG.log(Level.INFO, "[PAYMENT] charged order={0}", event.getAggregateId());
    }
}

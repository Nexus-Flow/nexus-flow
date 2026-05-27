package net.nexus_flow.advanced;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.Map;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxAppender;
import net.nexus_flow.core.outbox.OutboxStorage;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;

/**
 * Listens to {@link StockReservationFailedEvent} and triggers compensation: marks the order
 * cancelled and emits an {@link OrderCancelledEvent} downstream.
 */
public final class CompensationService
        extends AbstractDomainEventListener<StockReservationFailedEvent> {

    private static final Logger LOG = System.getLogger(CompensationService.class.getName());

    private final Map<String, OrderAggregate>         orders;
    private final OutboxStorage                       outbox;
    private final JavaSerializationOutboxPayloadCodec codec;

    public CompensationService(Map<String, OrderAggregate> orders, OutboxStorage outbox) {
        this.orders = orders;
        this.outbox = outbox;
        this.codec  = new JavaSerializationOutboxPayloadCodec();
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
        ExecutionContext ctx = FlowScope.current().orElseGet(ExecutionContext::root);
        OutboxAppender.appendDrainedEvents(
                agg.drainEvents(), ctx, outbox, Clock.systemUTC(), codec);
    }
}

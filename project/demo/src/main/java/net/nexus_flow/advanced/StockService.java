package net.nexus_flow.advanced;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxAppender;
import net.nexus_flow.core.outbox.OutboxStorage;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;

/**
 * Listens to {@link OrderPlacedEvent}, attempts a stock reservation, and emits either
 * {@link StockReservedEvent} or {@link StockReservationFailedEvent} accordingly. Demonstrates the
 * "event triggers next saga step" pattern without needing a dedicated command bus call.
 */
public final class StockService extends AbstractDomainEventListener<OrderPlacedEvent> {

    private static final Logger LOG = System.getLogger(StockService.class.getName());

    private final Map<String, OrderAggregate>         orders;
    private final OutboxStorage                       outbox;
    private final JavaSerializationOutboxPayloadCodec codec;
    private final AtomicInteger                       triesUntilFirstFailure;

    public StockService(Map<String, OrderAggregate> orders, OutboxStorage outbox,
            int triesBeforeFirstFailure) {
        this.orders                 = orders;
        this.outbox                 = outbox;
        this.codec                  = new JavaSerializationOutboxPayloadCodec();
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
        ExecutionContext ctx = FlowScope.current().orElseGet(ExecutionContext::root);
        OutboxAppender.appendDrainedEvents(
                agg.drainEvents(), ctx, outbox, Clock.systemUTC(), codec);
    }
}

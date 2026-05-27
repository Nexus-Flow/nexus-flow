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
 * Listens to {@link StockReservedEvent} and charges the payment. Emits {@link
 * PaymentChargedEvent} on success.
 */
public final class PaymentService extends AbstractDomainEventListener<StockReservedEvent> {

    private static final Logger LOG = System.getLogger(PaymentService.class.getName());

    private final Map<String, OrderAggregate>         orders;
    private final OutboxStorage                       outbox;
    private final JavaSerializationOutboxPayloadCodec codec;

    public PaymentService(Map<String, OrderAggregate> orders, OutboxStorage outbox) {
        this.orders = orders;
        this.outbox = outbox;
        this.codec  = new JavaSerializationOutboxPayloadCodec();
    }

    @Override
    public void handle(StockReservedEvent event) {
        OrderAggregate agg = orders.get(event.getAggregateId());
        if (agg == null) {
            return;
        }
        agg.markPaymentCharged();
        LOG.log(Level.INFO, "[PAYMENT] charged order={0}", event.getAggregateId());
        ExecutionContext ctx = FlowScope.current().orElseGet(ExecutionContext::root);
        OutboxAppender.appendDrainedEvents(
                agg.drainEvents(), ctx, outbox, Clock.systemUTC(), codec);
    }
}

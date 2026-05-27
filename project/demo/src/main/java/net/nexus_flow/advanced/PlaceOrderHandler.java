package net.nexus_flow.advanced;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxAppender;
import net.nexus_flow.core.outbox.OutboxStorage;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;

/**
 * Handles {@link PlaceOrderCommand}. Constructs the aggregate, then drains its events into the
 * outbox in the same logical "transaction" (in-memory in this demo; in production this would be
 * the same DB tx as the aggregate write).
 */
public final class PlaceOrderHandler extends AbstractNoReturnCommandHandler<PlaceOrderCommand> {

    private static final Logger LOG = System.getLogger(PlaceOrderHandler.class.getName());

    private final Map<String, OrderAggregate>          orders = new ConcurrentHashMap<>();
    private final OutboxStorage                        outbox;
    private final JavaSerializationOutboxPayloadCodec  codec;

    public PlaceOrderHandler(OutboxStorage outbox) {
        this.outbox = outbox;
        this.codec  = new JavaSerializationOutboxPayloadCodec();
    }

    public Map<String, OrderAggregate> orders() {
        return orders;
    }

    @Override
    public void handle(PlaceOrderCommand command) {
        OrderAggregate agg = new OrderAggregate(
                command.orderId(), command.productId(), command.quantity());
        orders.put(agg.orderId(), agg);
        ExecutionContext ctx = FlowScope.current().orElseGet(ExecutionContext::root);
        OutboxAppender.appendDrainedEvents(
                agg.drainEvents(), ctx, outbox, Clock.systemUTC(), codec);
        LOG.log(Level.INFO,
                "[ORDER] placed order={0} product={1} qty={2}",
                command.orderId(), command.productId(), command.quantity());
    }
}

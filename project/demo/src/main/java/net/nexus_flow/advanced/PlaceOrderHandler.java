package net.nexus_flow.advanced;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;

/**
 * Handles {@link PlaceOrderCommand} — creates the aggregate and lets the framework take it
 * from there.
 *
 * <p>The runtime's recursive event drain means the handler no longer needs to manually drain
 * the aggregate and append to the outbox. Recording an event on the aggregate (which the
 * aggregate's own state-transition methods do) is enough: the runtime catches the new entry
 * in {@code DomainEventContext}, appends it to the bound outbox, and fans it out to listeners
 * — repeating until the context is quiescent.
 */
public final class PlaceOrderHandler extends AbstractNoReturnCommandHandler<PlaceOrderCommand> {

    private static final Logger LOG = System.getLogger(PlaceOrderHandler.class.getName());

    private final Map<String, OrderAggregate> orders = new ConcurrentHashMap<>();

    /** No constructor params — the framework handles outbox plumbing now. */
    public PlaceOrderHandler() {
    }

    public Map<String, OrderAggregate> orders() {
        return orders;
    }

    @Override
    public void handle(PlaceOrderCommand command) {
        OrderAggregate agg = new OrderAggregate(
                command.orderId(), command.productId(), command.quantity());
        orders.put(agg.orderId(), agg);
        LOG.log(Level.INFO,
                "[ORDER] placed order={0} product={1} qty={2}",
                command.orderId(), command.productId(), command.quantity());
    }
}

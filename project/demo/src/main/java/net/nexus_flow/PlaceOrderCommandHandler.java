package net.nexus_flow;

import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Handles {@link PlaceOrderCommand}.
 *
 * <p>Creates a new {@link OrderAggregate}, stores it in the demo repository, and lets Nexus Flow
 * drain and fan out the recorded {@link OrderPlacedEvent}.
 *
 * <p>In production this handler would persist the aggregate through an {@link
 * net.nexus_flow.core.eventsourcing.AggregateRepository}, ensuring the event stream is durable.
 */
public class PlaceOrderCommandHandler extends AbstractNoReturnCommandHandler<PlaceOrderCommand> {

  private static final Logger LOG = System.getLogger(PlaceOrderCommandHandler.class.getName());

  private final InMemoryOrderRepository orders;

  /**
   * Creates a place-order handler backed by the demo repository.
   *
   * @param orders shared in-memory repository used by the demo
   * @throws NullPointerException if {@code orders} is {@code null}
   */
  public PlaceOrderCommandHandler(InMemoryOrderRepository orders) {
    this.orders = Objects.requireNonNull(orders, "orders");
  }

  /**
   * Handles a place-order command by creating an in-memory aggregate instance.
   *
   * <p>The aggregate records the corresponding domain event, which the runtime drains and dispatches
   * to listeners after the handler completes.
   *
   * @param command command containing the order id, product id, and quantity
   */
  @Override
  protected void handle(PlaceOrderCommand command) {
    LOG.log(
        Level.INFO,
        "Placing order {0} for product {1} x{2}",
        command.orderId(),
        command.productId(),
        command.quantity());

    OrderAggregate order =
        OrderAggregate.place(command.orderId(), command.productId(), command.quantity());
    orders.save(order);

    LOG.log(Level.INFO, "Order placed: {0}", order);
  }
}
package net.nexus_flow;

import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Handles {@link CancelOrderCommand}.
 *
 * <p>Loads the same in-memory order created by {@link PlaceOrderCommandHandler}, applies the
 * cancellation, stores the updated aggregate, and returns a typed {@link CancellationReceipt}.
 *
 * <p>In production this handler would load the aggregate from an {@link
 * net.nexus_flow.core.eventsourcing.AggregateRepository}, call {@link OrderAggregate#cancel(String)}
 * and persist the updated event stream.
 */
public class CancelOrderCommandHandler
    extends AbstractReturnCommandHandler<CancelOrderCommand, CancellationReceipt> {

  private static final Logger LOG = System.getLogger(CancelOrderCommandHandler.class.getName());

  private final InMemoryOrderRepository orders;

  /**
   * Creates a cancel-order handler backed by the demo repository.
   *
   * @param orders shared in-memory repository used by the demo
   * @throws NullPointerException if {@code orders} is {@code null}
   */
  public CancelOrderCommandHandler(InMemoryOrderRepository orders) {
    this.orders = Objects.requireNonNull(orders, "orders");
  }

  /**
   * Handles a cancel-order command by loading and cancelling the existing order.
   *
   * @param command command containing the order id and cancellation reason
   * @return typed cancellation receipt used by {@code dispatchAndReturnResult(...)}
   * @throws IllegalArgumentException if the order does not exist
   * @throws IllegalStateException if the aggregate rejects the cancellation
   */
  @Override
  protected CancellationReceipt handle(CancelOrderCommand command) {
    LOG.log(Level.INFO, "Cancelling order {0}: {1}", command.orderId(), command.reason());

    OrderAggregate order =
        orders
            .findById(command.orderId())
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + command.orderId()));

    order.cancel(command.reason());
    orders.save(order);

    LOG.log(Level.INFO, "Order cancelled: {0}", order);

    return new CancellationReceipt(order.getOrderId(), order.getStatus(), command.reason());
  }
}
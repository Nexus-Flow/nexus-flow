package net.nexus_flow;

import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Handles {@link GetOrderQuery}.
 *
 * <p>Returns a human-readable order summary from the demo repository. In production this handler
 * would query a dedicated read model, such as a projection updated by event listeners.
 */
public class GetOrderQueryHandler extends AbstractQueryHandler<GetOrderQuery, String> {

  private static final Logger LOG = System.getLogger(GetOrderQueryHandler.class.getName());

  private final InMemoryOrderRepository orders;

  /**
   * Creates a query handler backed by the demo repository.
   *
   * @param orders shared in-memory repository used by the demo
   * @throws NullPointerException if {@code orders} is {@code null}
   */
  public GetOrderQueryHandler(InMemoryOrderRepository orders) {
    this.orders = Objects.requireNonNull(orders, "orders");
  }

  /**
   * Returns a human-readable summary for the requested order.
   *
   * @param query query containing the target order id
   * @return a summary string for the order
   * @throws IllegalArgumentException if the order does not exist
   */
  @Override
  public String handle(GetOrderQuery query) {
    LOG.log(Level.DEBUG, "Fetching order {0}", query.orderId());

    OrderAggregate order =
        orders
            .findById(query.orderId())
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + query.orderId()));

    return "Order[id=%s, status=%s, product=%s, qty=%d]"
        .formatted(
            order.getOrderId(), order.getStatus(), order.getProductId(), order.getQuantity());
  }
}
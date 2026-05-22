package net.nexus_flow;

import net.nexus_flow.core.ddd.AbstractDomainEvent;

import java.io.Serial;

/**
 * Emitted when a new order is successfully placed.
 *
 * <p>The aggregate records this event inside {@link OrderAggregate#place}. Registered listeners or
 * downstream projections can react to it through the event bus.
 */
public class OrderPlacedEvent extends AbstractDomainEvent {

  @Serial private static final long serialVersionUID = 1L;

  private final String productId;
  private final int quantity;

  /**
   * Constructs a new {@code OrderPlacedEvent}.
   *
   * @param orderId the order identifier
   * @param productId the product identifier
   * @param quantity the quantity ordered
   */
  public OrderPlacedEvent(String orderId, String productId, int quantity) {
    super(orderId);
    this.productId = productId;
    this.quantity = quantity;
  }

  /**
   * Returns the order identifier.
   *
   * @return the order id
   */
  public String getOrderId() {
    return getAggregateId();
  }

  /**
   * Returns the product identifier.
   *
   * @return the product id
   */
  public String getProductId() {
    return productId;
  }

  /**
   * Returns the ordered quantity.
   *
   * @return the quantity
   */
  public int getQuantity() {
    return quantity;
  }

  /**
   * Returns a string representation of the event for debugging and logging.
   *
   * @return string representation
   */
  @Override
  public String toString() {
    return "OrderPlacedEvent{orderId='%s', productId='%s', quantity=%d}"
        .formatted(getAggregateId(), productId, quantity);
  }
}
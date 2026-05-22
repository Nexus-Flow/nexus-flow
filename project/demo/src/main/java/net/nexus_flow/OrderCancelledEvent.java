package net.nexus_flow;

import net.nexus_flow.core.ddd.AbstractDomainEvent;

import java.io.Serial;

/**
 * Emitted when an order is successfully canceled.
 *
 * <p>Downstream listeners, such as inventory restore services or notification projectors, can
 * subscribe to this event.
 */
public class OrderCancelledEvent extends AbstractDomainEvent {

  @Serial private static final long serialVersionUID = 1L;

  private final String reason;

  /**
   * Constructs a new {@code OrderCancelledEvent}.
   *
   * @param orderId the order identifier
   * @param reason the cancellation reason
   */
  public OrderCancelledEvent(String orderId, String reason) {
    super(orderId);
    this.reason = reason;
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
   * Returns the cancellation reason.
   *
   * @return the cancellation reason
   */
  public String getReason() {
    return reason;
  }

  /**
   * Returns a string representation of the event for debugging and logging.
   *
   * @return string representation
   */
  @Override
  public String toString() {
    return "OrderCancelledEvent{orderId='%s', reason='%s'}".formatted(getAggregateId(), reason);
  }
}
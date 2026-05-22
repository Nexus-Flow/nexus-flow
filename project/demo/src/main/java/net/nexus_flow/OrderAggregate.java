package net.nexus_flow;

import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;

import java.io.Serial;

/**
 * Order aggregate - enforces order lifecycle rules.
 *
 * <h2>Lifecycle</h2>
 *
 * <pre>
 *   PENDING --place()--> PLACED --cancel()--> CANCELLED
 * </pre>
 *
 * <p>State transitions are always accompanied by a domain event recorded via {@link #recordEvent},
 * which is later drained by the runtime and fanned out to every registered listener through the
 * event bus.
 *
 * <p><b>Event-sourcing note:</b> when wired with an {@link
 * net.nexus_flow.core.eventsourcing.AggregateRepository}, each call to {@code apply(DomainEvent)}
 * rebuilds the in-memory state from the persisted event stream, so the aggregate never needs to load
 * from a traditional read model.
 */
public class OrderAggregate extends Aggregate {

  @Serial private static final long serialVersionUID = 1L;

  /** Current lifecycle state of an order. */
  public enum Status {
    PENDING,
    PLACED,
    CANCELLED
  }

  private String orderId;
  private String productId;
  private int quantity;
  private Status status = Status.PENDING;

  /** No-arg constructor required for event-sourcing replay via {@code apply()}. */
  public OrderAggregate() {}

  /**
   * Creates a new order and records an {@link OrderPlacedEvent}.
   *
   * @param orderId client-generated idempotency key
   * @param productId catalogue reference of the purchased product
   * @param quantity number of units, must be greater than zero
   * @return a new {@code OrderAggregate} with status {@code PLACED}
   */
  public static OrderAggregate place(String orderId, String productId, int quantity) {
    OrderAggregate order = new OrderAggregate();
    order.orderId = orderId;
    order.productId = productId;
    order.quantity = quantity;
    order.status = Status.PLACED;
    order.recordEvent(new OrderPlacedEvent(orderId, productId, quantity));
    return order;
  }

  /**
   * Cancels the order if it is still in the {@code PLACED} state.
   *
   * @param reason human-readable cancellation reason
   * @throws IllegalStateException if the order is not in {@code PLACED} state
   */
  public void cancel(String reason) {
    if (status != Status.PLACED) {
      throw new IllegalStateException(
          "Cannot cancel order '%s': current status is %s".formatted(orderId, status));
    }

    status = Status.CANCELLED;
    recordEvent(new OrderCancelledEvent(orderId, reason));
  }

  /**
   * Rebuilds in-memory state from a stored event during replay.
   *
   * <p>Override {@code apply} for every event type the aggregate produces. The base class {@link
   * Aggregate#replay} calls this hook automatically when rehydrating from an event store.
   */
  @Override
  protected void apply(DomainEvent event) {
    switch (event) {
      case OrderPlacedEvent placed -> {
        orderId = placed.getOrderId();
        productId = placed.getProductId();
        quantity = placed.getQuantity();
        status = Status.PLACED;
      }
      case OrderCancelledEvent ignored -> status = Status.CANCELLED;
      default -> {
        // Ignore unknown events for forward compatibility.
      }
    }
  }

  /**
   * Returns the order identifier.
   *
   * @return the order id
   */
  public String getOrderId() {
    return orderId;
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
   * Returns the current status of the order.
   *
   * @return the order status
   */
  public Status getStatus() {
    return status;
  }

  /**
   * Returns a string representation of the order aggregate for debugging and logging.
   *
   * @return string representation
   */
  @Override
  public String toString() {
    return "OrderAggregate{orderId='%s', productId='%s', quantity=%d, status=%s}"
        .formatted(orderId, productId, quantity, status);
  }
}
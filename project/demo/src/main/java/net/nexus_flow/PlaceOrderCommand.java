package net.nexus_flow;

/**
 * Command to place a new order.
 *
 * <p>Carries the minimal information needed to create an order: a client-provided idempotency key
 * ({@code orderId}), the product, and how many units the customer wants.
 */
public record PlaceOrderCommand(String orderId, String productId, int quantity) {

  /**
   * Validates the command payload.
   *
   * @throws IllegalArgumentException if {@code orderId} or {@code productId} is blank, or if {@code
   *     quantity} is not positive
   */
  public PlaceOrderCommand {
    if (orderId == null || orderId.isBlank()) {
      throw new IllegalArgumentException("orderId must not be blank");
    }
    if (productId == null || productId.isBlank()) {
      throw new IllegalArgumentException("productId must not be blank");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be > 0");
    }
  }
}
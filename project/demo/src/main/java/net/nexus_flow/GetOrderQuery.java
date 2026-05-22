package net.nexus_flow;

/**
 * Query that retrieves a read-model summary for a given order.
 *
 * <p>In production this would hit a dedicated read model or projection. The demo handler returns a
 * plain {@link String} for brevity.
 */
public record GetOrderQuery(String orderId) {

  /**
   * Validates the query payload.
   *
   * @throws IllegalArgumentException if {@code orderId} is blank
   */
  public GetOrderQuery {
    if (orderId == null || orderId.isBlank()) {
      throw new IllegalArgumentException("orderId must not be blank");
    }
  }
}
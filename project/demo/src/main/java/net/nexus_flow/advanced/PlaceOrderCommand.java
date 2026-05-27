package net.nexus_flow.advanced;

/** Place an order — request shape for the advanced demo. */
public record PlaceOrderCommand(String orderId, String productId, int quantity) {
    public PlaceOrderCommand {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must be non-blank");
        }
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId must be non-blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
    }
}

package net.nexus_flow;

/**
 * Command to cancel an existing order.
 *
 * <p>Only placed orders can be canceled. If the order is already canceled or in another terminal
 * state, the handler rejects the command with a domain error.
 */
public record CancelOrderCommand(String orderId, String reason) {

    /**
     * Validates the command payload.
     *
     * @throws IllegalArgumentException if {@code orderId} or {@code reason} is blank
     */
    public CancelOrderCommand {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
package net.nexus_flow;

/**
 * Typed command result returned when a cancel-order command succeeds.
 *
 * <p>The demo uses this record to exercise the {@code dispatchAndReturnResult(...)} path with a
 * real return-value command handler.
 *
 * @param orderId cancelled order id
 * @param status resulting order status
 * @param reason cancellation reason
 */
public record CancellationReceipt(
    String orderId, OrderAggregate.Status status, String reason) {}
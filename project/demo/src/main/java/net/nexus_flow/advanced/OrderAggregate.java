package net.nexus_flow.advanced;

import java.io.Serial;
import net.nexus_flow.core.ddd.Aggregate;

/**
 * Aggregate root for an order. Records domain events that drive the saga downstream. Kept tiny
 * deliberately — the interesting flow happens around the aggregate, not inside it.
 */
public final class OrderAggregate extends Aggregate {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String orderId;
    private String       status = "PLACED";

    public OrderAggregate(String orderId, String productId, int quantity) {
        this.orderId = orderId;
        recordEvent(new OrderPlacedEvent(orderId, productId, quantity));
    }

    public void markStockReserved() {
        status = "STOCK_RESERVED";
        recordEvent(new StockReservedEvent(orderId));
    }

    public void markStockFailed(String reason) {
        status = "STOCK_FAILED";
        recordEvent(new StockReservationFailedEvent(orderId, reason));
    }

    public void cancel(String reason) {
        status = "CANCELLED";
        recordEvent(new OrderCancelledEvent(orderId, reason));
    }

    public void markPaymentCharged() {
        status = "COMPLETED";
        recordEvent(new PaymentChargedEvent(orderId));
    }

    public String status() {
        return status;
    }

    public String orderId() {
        return orderId;
    }
}

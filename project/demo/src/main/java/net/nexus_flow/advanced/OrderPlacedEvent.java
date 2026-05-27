package net.nexus_flow.advanced;

import java.io.Serial;
import net.nexus_flow.core.ddd.AbstractDomainEvent;

/** Domain event emitted by {@code OrderAggregate} when an order is placed. */
public final class OrderPlacedEvent extends AbstractDomainEvent {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String productId;
    private final int    quantity;

    OrderPlacedEvent(String orderId, String productId, int quantity) {
        super(orderId);
        this.productId = productId;
        this.quantity  = quantity;
    }

    public String productId() {
        return productId;
    }

    public int quantity() {
        return quantity;
    }
}

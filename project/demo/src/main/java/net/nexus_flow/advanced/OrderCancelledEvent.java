package net.nexus_flow.advanced;

import java.io.Serial;
import net.nexus_flow.core.ddd.AbstractDomainEvent;

/** Order cancellation — terminal compensation event. */
public final class OrderCancelledEvent extends AbstractDomainEvent {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String reason;

    OrderCancelledEvent(String orderId, String reason) {
        super(orderId);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}

package net.nexus_flow.advanced;

import java.io.Serial;
import net.nexus_flow.core.ddd.AbstractDomainEvent;

/** Stock reservation failure — drives the saga to compensate (cancel the order). */
public final class StockReservationFailedEvent extends AbstractDomainEvent {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String reason;

    StockReservationFailedEvent(String orderId, String reason) {
        super(orderId);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}

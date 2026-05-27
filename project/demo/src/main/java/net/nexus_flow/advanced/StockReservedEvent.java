package net.nexus_flow.advanced;

import java.io.Serial;
import net.nexus_flow.core.ddd.AbstractDomainEvent;

/** Stock reservation success — drives the saga to ChargePayment. */
public final class StockReservedEvent extends AbstractDomainEvent {
    @Serial
    private static final long serialVersionUID = 1L;

    StockReservedEvent(String orderId) {
        super(orderId);
    }
}

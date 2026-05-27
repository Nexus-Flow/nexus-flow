package net.nexus_flow.advanced;

import java.io.Serial;
import net.nexus_flow.core.ddd.AbstractDomainEvent;

/** Payment success — saga reaches Completed. */
public final class PaymentChargedEvent extends AbstractDomainEvent {
    @Serial
    private static final long serialVersionUID = 1L;

    PaymentChargedEvent(String orderId) {
        super(orderId);
    }
}

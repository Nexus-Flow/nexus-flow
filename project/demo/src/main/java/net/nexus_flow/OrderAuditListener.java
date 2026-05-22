package net.nexus_flow;

import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Listens for placed-order events and writes audit log entries.
 *
 * <p>Registered at order {@code 1}. A second listener registered at a higher order could send a
 * confirmation email, demonstrating the ordered fan-out mechanism.
 *
 * <p>One listener per event type is the idiomatic Nexus Flow pattern.
 */
public class OrderAuditListener extends AbstractDomainEventListener<OrderPlacedEvent> {

  private static final Logger LOG = System.getLogger(OrderAuditListener.class.getName());

  /**
   * Writes an audit log entry for each placed order event.
   *
   * @param event the order-placed event to audit
   */
  @Override
  public void handle(OrderPlacedEvent event) {
    LOG.log(
        Level.INFO,
        "[AUDIT] Order placed - id={0}, product={1}, qty={2}, seq={3}",
        event.getOrderId(),
        event.getProductId(),
        event.getQuantity(),
        event.getSequenceNumber());
  }

  /**
   * Runs before any listener registered with a higher order value.
   *
   * @return listener order
   */
  @Override
  public int order() {
    return 1;
  }
}
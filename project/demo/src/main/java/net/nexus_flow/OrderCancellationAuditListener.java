package net.nexus_flow;

import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Listens for canceled-order events and writes audit log entries.
 *
 * <p>This listener is separated from {@link OrderAuditListener} because each Nexus Flow listener is
 * typed to one domain event.
 */
public class OrderCancellationAuditListener
    extends AbstractDomainEventListener<OrderCancelledEvent> {

  private static final Logger LOG =
      System.getLogger(OrderCancellationAuditListener.class.getName());

  /**
   * Writes an audit log entry for each canceled order event.
   *
   * @param event the order-canceled event to audit
   */
  @Override
  public void handle(OrderCancelledEvent event) {
    LOG.log(
        Level.INFO,
        "[AUDIT] Order cancelled - id={0}, reason={1}, seq={2}",
        event.getOrderId(),
        event.getReason(),
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
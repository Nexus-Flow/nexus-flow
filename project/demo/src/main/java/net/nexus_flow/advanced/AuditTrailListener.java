package net.nexus_flow.advanced;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * Records every domain event observed on the bus. The audit trail demonstrates that the demo's
 * full pipeline — command → aggregate event → outbox → worker → bus → listener — produces a
 * deterministic, replayable sequence of events that operators can later inspect.
 */
public final class AuditTrailListener extends AbstractDomainEventListener<DomainEvent> {

    private static final Logger LOG = System.getLogger(AuditTrailListener.class.getName());

    @Override
    public void handle(DomainEvent event) {
        LOG.log(Level.INFO,
                "[AUDIT] type={0} aggregate={1} id={2}",
                event.eventType(),
                event.getAggregateId(),
                event.getId());
    }
}

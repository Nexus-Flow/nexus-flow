package net.nexus_flow.core.ddd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.util.List;
import net.nexus_flow.core.cqrs.event.DomainEventContext;
import org.junit.jupiter.api.Test;

/**
 * Pins the surgical-removal contract that {@link Aggregate#markCommitted(long)} and {@link
 * Aggregate#drainEvents()} use to keep sibling aggregates' events in the ambient sink.
 *
 * <p>Pre-fix bug: both methods called {@link DomainEventContext#clearEvents()} which blasted
 * the entire ambient sink — when a handler touched two aggregates, committing the first
 * wiped the second's events from the sink and the runtime's drain would skip publishing
 * them. This test would have caught that regression and now pins the corrected behavior.
 */
class MultiAggregateAmbientContextTest {

    public static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Bumped(String aggId) {
            super(aggId);
        }
    }

    public static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String aggId;

        public Counter(String aggId) {
            this.aggId = aggId;
        }

        public void bump() {
            recordEvent(new Bumped(aggId));
        }

        public String aggId() {
            return aggId;
        }
    }

    @Test
    void markCommitted_removesOnlyThisAggregatesEvents_fromAmbientSink() {
        DomainEventContext ambient = DomainEventContext.current();
        ambient.clearEvents();

        Counter a1 = new Counter("A1");
        Counter a2 = new Counter("A2");
        a1.bump();          // E1 → A1.uncommitted + ambient
        a2.bump();          // E2 → A2.uncommitted + ambient
        a1.bump();          // E1b → A1.uncommitted + ambient (3 events in ambient)

        assertEquals(3, ambient.getEvents().size(), "ambient sink must hold E1 + E2 + E1b");

        // Commit A1 — surgically removes A1's events (E1, E1b) from ambient.
        a1.markCommitted(2L); // 2 events were recorded for A1

        List<DomainEvent> remaining = ambient.getEvents();
        assertEquals(1, remaining.size(),
                     "after A1 commit ONLY A1's events must leave the sink; A2's event MUST stay");
        assertEquals(a2.aggId(), remaining.get(0).getAggregateId(),
                     "the remaining event must belong to A2 (the un-committed aggregate)");
        assertTrue(ambient.hasEventsRecorded(),
                   "hasEventsRecorded captures 'events were recorded during this execution';"
                           + " surgical removal must NOT reset that flag");

        ambient.clearEvents();
    }

    @Test
    void drainEvents_removesOnlyThisAggregatesEvents_fromAmbientSink() {
        DomainEventContext ambient = DomainEventContext.current();
        ambient.clearEvents();

        Counter a1 = new Counter("A1");
        Counter a2 = new Counter("A2");
        a1.bump();
        a2.bump();
        a2.bump();

        assertEquals(3, ambient.getEvents().size());

        // Drain A2 — surgically removes A2's two events; A1's event must remain.
        List<DomainEvent> drained = a2.drainEvents();
        assertEquals(2, drained.size(), "drainEvents returns the aggregate's own events");

        List<DomainEvent> remaining = ambient.getEvents();
        assertEquals(1, remaining.size(),
                     "after A2 drain, only A1's event must remain in the ambient sink");
        assertEquals(a1.aggId(), remaining.get(0).getAggregateId());

        ambient.clearEvents();
    }

    @Test
    void removeRecordedEvents_isIdempotent_acceptsAlreadyRemovedReferences() {
        DomainEventContext ambient = DomainEventContext.current();
        ambient.clearEvents();

        Counter a1 = new Counter("A1");
        a1.bump();

        List<DomainEvent> snapshot = List.copyOf(ambient.getEvents());
        ambient.removeRecordedEvents(snapshot);
        ambient.removeRecordedEvents(snapshot); // second call MUST be a no-op
        assertEquals(0, ambient.getEvents().size());

        ambient.clearEvents();
    }
}

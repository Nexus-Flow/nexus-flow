package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.DomainEvent;
import org.junit.jupiter.api.Test;

class DomainEventContextImplementationsTest {

    static final class TestEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        TestEvent(String aggregateId) {
            super(aggregateId);
        }
    }

    @Test
    void threadLocal_context_tracks_recorded_state_per_thread_and_cleans_up() throws InterruptedException {
        ThreadLocalDomainEventContext context              = new ThreadLocalDomainEventContext();
        AtomicBoolean                 otherThreadHasEvents = new AtomicBoolean(true);
        AtomicBoolean                 otherThreadRecorded  = new AtomicBoolean(true);

        assertFalse(context.hasEventsRecorded());
        assertTrue(context.getEvents().isEmpty());

        context.recordEvent(new TestEvent("main"));

        assertTrue(context.hasEventsRecorded());
        assertEquals(1, context.getEvents().size());

        Thread other =
                new Thread(
                        () -> {
                            otherThreadHasEvents.set(!context.getEvents().isEmpty());
                            otherThreadRecorded.set(context.hasEventsRecorded());
                            context.recordEvent(new TestEvent("other"));
                            assertTrue(context.hasEventsRecorded());
                            context.clearEvents();
                            assertFalse(context.hasEventsRecorded());
                        });
        other.start();
        other.join();

        assertFalse(otherThreadHasEvents.get());
        assertFalse(otherThreadRecorded.get());
        assertEquals(1, context.getEvents().size());

        context.clearEvents();

        assertFalse(context.hasEventsRecorded());
        assertTrue(context.getEvents().isEmpty());
    }

    @Test
    void scoped_context_tracks_bound_scope_and_fallback_state() {
        ScopedDomainEventContext      context      = new ScopedDomainEventContext();
        List<DomainEvent>             scopedEvents = new ArrayList<>();
        ScopedDomainEventContext.Sink scopedSink   = new ScopedDomainEventContext.Sink(scopedEvents);

        ScopedValue.where(context.getScopedValue(), scopedSink)
                .run(
                     () -> {
                         assertFalse(context.hasEventsRecorded());
                         assertSame(scopedEvents, context.getEvents());

                         context.recordEvent(new TestEvent("scoped"));

                         assertTrue(context.hasEventsRecorded());
                         assertEquals(1, context.getEvents().size());

                         context.resetEventsRecorded();
                         assertFalse(context.hasEventsRecorded());
                         assertEquals(1, context.getEvents().size());

                         context.clearEvents();
                         assertFalse(context.hasEventsRecorded());
                         assertTrue(scopedEvents.isEmpty());
                     });

        assertFalse(context.hasEventsRecorded());
        assertTrue(context.getEvents().isEmpty());

        context.recordEvent(new TestEvent("fallback"));

        assertTrue(context.hasEventsRecorded());
        assertEquals(1, context.getEvents().size());

        context.clearEvents();

        assertFalse(context.hasEventsRecorded());
        assertTrue(context.getEvents().isEmpty());
    }
}

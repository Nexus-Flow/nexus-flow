package net.nexus_flow.core.ddd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.List;
import net.nexus_flow.core.cqrs.event.DomainEventContext;
import net.nexus_flow.core.runtime.result.CommandResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link Aggregate} event recording contract: legacy handlers using {@code recordEvent}
 * alongside new handlers returning {@code CommandResult.Success(value, events)} must work
 * correctly.
 *
 * <ul>
 * <li>(a) Legacy handlers see events through the JVM-wide {@link DomainEventContext}.
 * <li>(b) New handlers drain events without consulting the holder.
 * <li>(c) Mixed flows do not double-emit: draining clears the holder.
 * </ul>
 */
class AggregateEventRecordingTest {

    /** A minimal {@link DomainEvent} for assertions. */
    static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Bumped(String aggregateId) {
            super(aggregateId);
        }
    }

    /** A minimal aggregate. */
    static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void bump(String id) {
            recordEvent(new Bumped(id));
        }
    }

    @BeforeEach
    void clearHolder() {
        DomainEventContext.current().clearEvents();
    }

    @AfterEach
    void resetHolder() {
        DomainEventContext.current().clearEvents();
    }

    @Test
    void legacyHandler_seesEventsThroughTheHolder() {
        // (a) legacy handler keeps using recordEvent + JVM-wide sink drain
        Counter counter = new Counter();
        counter.bump("c-1");
        counter.bump("c-2");

        DomainEventContext holder = DomainEventContext.current();
        List<DomainEvent>  seen   = holder.getEvents();

        assertEquals(2, seen.size(), "legacy handler must observe events through the holder");
        assertTrue(seen.stream().allMatch(Bumped.class::isInstance));
    }

    @Test
    void newHandler_drainsEvents_succeeds() {
        // (b) new handler returns CommandResult.Success(value, events) without leaning on the holder
        Counter counter = new Counter();
        counter.bump("c-1");
        counter.bump("c-2");

        // What a aware handler does at the end of its run:
        List<DomainEvent>      drained = counter.drainEvents();
        CommandResult<Integer> result  = CommandResult.success(42, drained);

        assertEquals(2, drained.size());
        var success = assertInstanceOf(CommandResult.Success.class, result);
        assertEquals(42, success.value());
        assertEquals(drained, success.events());
        assertSame(
                   drained.getFirst(),
                   success.events().getFirst(),
                   "events list must preserve identity, not copy elements");
    }

    @Test
    void drain_clearsHolder_preventingDoubleEmission() {
        // (c) mixed mode: drain on aggregate clears the holder so legacy listeners cannot re-emit
        Counter counter = new Counter();
        counter.bump("c-1");

        // Pre-condition: sink has the event because recordEvent dual-writes.
        DomainEventContext holder = DomainEventContext.current();
        assertEquals(1, holder.getEvents().size());

        // New handler drains the aggregate-local list:
        List<DomainEvent> drained = counter.drainEvents();
        assertEquals(1, drained.size());

        // Post-condition: sink is now empty — a legacy drain path would
        // see nothing and therefore cannot double-emit.
        assertEquals(0, holder.getEvents().size(), "drain clears the JVM-wide sink");
    }

    @Test
    void drainEvents_isIdempotent_betweenCalls() {
        Counter counter = new Counter();
        counter.bump("c-1");

        assertEquals(1, counter.drainEvents().size());
        // Second drain on the same aggregate must return an empty list.
        assertEquals(
                     0,
                     counter.drainEvents().size(),
                     "second drain returns nothing — events were already taken");
    }

    @Test
    void reRecording_sameAbstractDomainEvent_isRejected() {
        Counter counter = new Counter();
        Bumped  event   = new Bumped("c-1");

        counter.recordEvent(event);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> counter.recordEvent(event));
        assertTrue(ex.getMessage().contains("only fresh events"));
    }

    @Test
    void success_noEventsOverload_yieldsEmpty() {
        // Sanity: the no-events factory overload returns empty list
        CommandResult<String> r = CommandResult.success("ok");
        assertNotNull(r);
        var s = (CommandResult.Success<String>) r;
        assertEquals(0, s.events().size());
    }
}

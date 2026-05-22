package net.nexus_flow.core.ddd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Serial;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link Aggregate#recordEvent(DomainEvent)} sequence-counter rollback contract:
 *
 * <p>When {@link Aggregate#apply(DomainEvent)} throws, the aggregate's internal {@code
 * nextSequenceNumber} counter MUST be rolled back so that a subsequent successful {@code
 * recordEvent} on the same aggregate does NOT skip a number. Without the rollback the event stream
 * would contain silent gaps that break downstream listeners and projections that detect missing
 * events via contiguous numbering.
 *
 * <p>Why this matters end-to-end:
 *
 * <ul>
 * <li>Apply failures signal a programming error (a guard predicate, an invariant violation), NOT
 * a transport-level retry — the caller catches the exception and either gives up or produces
 * a different event.
 * <li>If the counter is not rolled back, the next legitimate event gets stamped with a higher
 * number than expected. Downstream listeners that observe events in order (sagas,
 * projections, append-only audit logs) silently see a missing sequence and may stall, loop,
 * or surface incorrect "we missed an event" alerts.
 * <li>The failed event itself STAYS stamped on its own instance — so re-recording it on another
 * aggregate is still rejected by the {@code assignSequenceNumber} guard. The rollback is
 * purely a counter rewind, NOT a re-record permission.
 * </ul>
 */
class AggregateRecordEventRollsBackSequenceOnApplyFailureTest {

    static final class BoomException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        BoomException() {
            super("apply-rejected");
        }
    }

    static final class GoodEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        GoodEvent(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class BadEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        BadEvent(String aggregateId) {
            super(aggregateId);
        }
    }

    /** Aggregate that throws from {@code apply()} when handed a {@link BadEvent}. */
    static final class StrictAggregate extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private final UUID        id;

        StrictAggregate(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void goodMove() {
            recordEvent(new GoodEvent(id.toString()));
        }

        void rejectedMove() {
            recordEvent(new BadEvent(id.toString()));
        }

        @Override
        protected void apply(DomainEvent event) {
            if (event instanceof BadEvent) {
                throw new BoomException();
            }
            // GoodEvent: no-op state transition
        }
    }

    @Test
    void successfulRecordings_assignContiguousSequenceNumbers_startingFromZero() {
        StrictAggregate agg = new StrictAggregate(UUID.randomUUID());
        agg.goodMove();
        agg.goodMove();
        agg.goodMove();

        var drained = agg.drainEvents();
        assertEquals(3, drained.size());
        for (int i = 0; i < drained.size(); i++) {
            AbstractDomainEvent ev = (AbstractDomainEvent) drained.get(i);
            assertEquals(i, ev.getSequenceNumber(), "event " + i + " must have sequence " + i);
        }
    }

    @Test
    void applyFailureMidStream_rollsBackCounter_subsequentEventGetsExpectedSequence() {
        StrictAggregate agg = new StrictAggregate(UUID.randomUUID());

        // Sequence 0, 1 succeed.
        agg.goodMove();
        agg.goodMove();

        // Sequence 2: apply throws. Pre-fix: counter advances to 3. Post-fix: counter rolls back to 2.
        assertThrows(BoomException.class, agg::rejectedMove);

        // Sequence 2 (post-fix) or 3 (pre-fix) — the NEXT successful event reveals the regression.
        agg.goodMove();

        var drained = agg.drainEvents();
        assertEquals(
                     3,
                     drained.size(),
                     "exactly 3 successful events must be in the uncommitted buffer (the failed one is gone)");
        AbstractDomainEvent last = (AbstractDomainEvent) drained.getLast();
        assertEquals(
                     2,
                     last.getSequenceNumber(),
                     "after apply() failure, the next successful event MUST reuse the failed slot's sequence"
                             + " number — otherwise listeners see a silent gap in the stream");
    }

    @Test
    void applyFailureOnFirstEvent_leavesCounterAtZero() {
        StrictAggregate agg = new StrictAggregate(UUID.randomUUID());
        assertThrows(BoomException.class, agg::rejectedMove);

        // The recovered first event must start at sequence 0, not 1.
        agg.goodMove();

        var drained = agg.drainEvents();
        assertEquals(1, drained.size());
        AbstractDomainEvent only = (AbstractDomainEvent) drained.getFirst();
        assertEquals(0, only.getSequenceNumber(), "first successful event must start at sequence 0");
    }

    @Test
    void failedEventInstance_cannotBeReRecordedOnAnotherAggregate() {
        StrictAggregate first  = new StrictAggregate(UUID.randomUUID());
        StrictAggregate second = new StrictAggregate(UUID.randomUUID());

        // Stamp the BadEvent on `first` (apply throws but the event is now stamped).
        BadEvent bad = new BadEvent(first.getAggregateId().toString());
        assertThrows(BoomException.class, () -> first.recordEvent(bad));

        // Try to re-record the same instance on `second` — assignSequenceNumber guard must reject it
        // because the event already carries a sequence number.
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> second.recordEvent(bad));
        assertEquals(
                     true,
                     ex.getMessage().contains("already has sequenceNumber"),
                     "rollback must NOT unstamp the failed event — cross-aggregate replay must still be"
                             + " blocked");
    }
}

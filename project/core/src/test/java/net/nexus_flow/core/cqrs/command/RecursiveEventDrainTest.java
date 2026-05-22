package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.outbox.EventDeliveryStrategy;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxConfig;
import net.nexus_flow.core.outbox.OutboxRecord;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Pins the post-handler drain loop that guarantees listener-emitted events reach the outbox.
 * Each iteration of the loop must:
 *
 * <ol>
 * <li>Snapshot the {@code DomainEventContext}.
 * <li>Clear the context BEFORE dispatching, so listener emissions accumulate in a fresh
 * buffer and become the input to the next iteration.
 * <li>Append the snapshot to the outbox (if configured) AND fan out inline (unless the
 * kill-switch is on).
 * </ol>
 *
 * <p>The loop terminates when the context is quiescent OR when the runtime's {@code
 * eventDrainMaxDepth} ceiling is crossed — that ceiling raises {@link
 * EventDrainOverflowException} so a pathological cycle never spins the dispatch thread silently.
 */
class RecursiveEventDrainTest {

    // ─── Test event taxonomy ────────────────────────────────────────────────

    /**
     * Base for the test's event types: each instance gets a unique {@code idempotencyKey} derived
     * from its UUID id so the outbox does not collapse "Event2 from listener A" with "Event2
     * from listener B" on the same aggregate. In production the deduplication key would be
     * {@code aggregateId:sequenceNumber} stamped by the aggregate — fine when each listener
     * works on its own aggregate, but the test deliberately uses fresh aggregates per step so
     * the sequence numbers would otherwise collide.
     */
    abstract static class UniqueKeyEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        UniqueKeyEvent(String aggregateId) {
            super(aggregateId);
        }

        @Override
        public final String idempotencyKey() {
            return getClass().getSimpleName() + ":" + getId();
        }
    }

    static final class Event1 extends UniqueKeyEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Event1(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Event2 extends UniqueKeyEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Event2(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Event3 extends UniqueKeyEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Event3(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class CycleEvent extends UniqueKeyEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        CycleEvent(String aggregateId) {
            super(aggregateId);
        }
    }

    // ─── Aggregate that emits a configurable chain of events ────────────────

    static final class ChainAggregate extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        ChainAggregate() {
            // Test aggregate — the business id travels with each emitted event, not with the
            // aggregate instance, because every step in the chain spins up a fresh aggregate.
        }

        void emit(DomainEvent event) {
            recordEvent(event);
        }
    }

    record StartCommand(String aggregateId) {
    }

    // ─── Handlers / listeners (each step in the chain is its own type) ──────

    /**
     * Synchronous return handler — using a value-returning handler keeps the test deterministic:
     * {@code dispatchAndReturnResult} blocks until the handler completes AND the post-handler
     * drain runs, so the subsequent assertions observe the final state of the outbox without
     * needing Awaitility.
     */
    static final class StartHandler extends AbstractReturnCommandHandler<StartCommand, String> {
        @Override
        public String handle(StartCommand cmd) {
            ChainAggregate agg = new ChainAggregate();
            agg.emit(new Event1(cmd.aggregateId()));
            return "ok";
        }
    }

    static final class Event1Listener extends AbstractDomainEventListener<Event1> {
        @Override
        public void handle(Event1 event) {
            // The listener emits a follow-up event on its OWN fresh aggregate. The event lands in
            // the same DomainEventContext the command handler bound, so the runtime's drain loop
            // sees it on the next iteration.
            ChainAggregate agg = new ChainAggregate();
            agg.emit(new Event2(event.getAggregateId()));
        }
    }

    static final class Event2Listener extends AbstractDomainEventListener<Event2> {
        @Override
        public void handle(Event2 event) {
            ChainAggregate agg = new ChainAggregate();
            agg.emit(new Event3(event.getAggregateId()));
        }
    }

    static final class CycleListener extends AbstractDomainEventListener<CycleEvent> {
        @Override
        public void handle(CycleEvent event) {
            // Emits ITSELF — guaranteed unbounded chain. The depth ceiling MUST break the cycle.
            ChainAggregate agg = new ChainAggregate();
            agg.emit(new CycleEvent(event.getAggregateId()));
        }
    }

    static final class CycleStartHandler extends AbstractReturnCommandHandler<StartCommand, String> {
        @Override
        public String handle(StartCommand cmd) {
            ChainAggregate agg = new ChainAggregate();
            agg.emit(new CycleEvent(cmd.aggregateId()));
            return "ok";
        }
    }

    static final class EventCounter extends AbstractDomainEventListener<DomainEvent> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void handle(DomainEvent event) {
            calls.incrementAndGet();
        }
    }

    // ─── Tests ──────────────────────────────────────────────────────────────

    @Test
    void listenerEmittedEvents_areAppendedToTheOutbox() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        OutboxConfig          cfg     = OutboxConfig.builder(
                                                             storage, new JavaSerializationOutboxPayloadCodec())
                // This test pins the recursive INLINE drain — events the listener emits must
                // surface back into the same recursive iteration. The framework's safe default
                // ({@link EventDeliveryStrategy#outboxOnly()}) suppresses the inline fan-out
                // entirely, so we opt into the dual-fan-out shape explicitly.
                .deliveryStrategy(EventDeliveryStrategy.inlinePlusOutbox())
                .autoStartWorker(false)
                .build();

        try (FlowRuntime runtime = FlowRuntime.builder()
                .outbox(cfg)
                .handlers(new StartHandler(), new Event1Listener(), new Event2Listener())
                .build()) {
            runtime.commands().dispatchAndReturn(
                                                 net.nexus_flow.core.cqrs.command.Command.<StartCommand>builder()
                                                         .body(new StartCommand("agg-1"))
                                                         .build());

            List<OutboxRecord> appended = drainAll(storage);
            // ALL three events must be present — Event1 from the handler, Event2 + Event3 from
            // the listener cascade. Before the recursive drain fix, only Event1 made it.
            assertEquals(3, appended.size(),
                         "expected Event1 + Event2 + Event3 appended to outbox; got " + appended);
            assertEquals("Event1", appended.get(0).payloadType().getSimpleName());
            assertEquals("Event2", appended.get(1).payloadType().getSimpleName());
            assertEquals("Event3", appended.get(2).payloadType().getSimpleName());
        }
    }

    @Test
    void infiniteCycle_isBrokenBy_depthCeiling_throwsOverflow() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        OutboxConfig          cfg     = OutboxConfig.builder(
                                                             storage, new JavaSerializationOutboxPayloadCodec())
                // This test pins the recursive INLINE drain — events the listener emits must
                // surface back into the same recursive iteration. The framework's safe default
                // ({@link EventDeliveryStrategy#outboxOnly()}) suppresses the inline fan-out
                // entirely, so we opt into the dual-fan-out shape explicitly.
                .deliveryStrategy(EventDeliveryStrategy.inlinePlusOutbox())
                .autoStartWorker(false)
                .build();

        try (FlowRuntime runtime = FlowRuntime.builder()
                .outbox(cfg)
                .eventDrainMaxDepth(5)
                .handlers(new CycleStartHandler(), new CycleListener())
                .build()) {
            // The cycle handler's first event triggers a listener that recursively emits the
            // same event class. The drain loop must catch this at depth 6 (one past the ceiling
            // of 5) and surface EventDrainOverflowException.
            // The drain throw is wrapped by the command-handler executor in
            // CommandHandlerExecutionError (the standard executor failure shape). Unwrap to the
            // root cause to assert on the actual semantic exception.
            net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError wrapper  =
                    assertThrows(
                                 net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError.class,
                                 () -> runtime.commands().dispatchAndReturn(
                                                                            net.nexus_flow.core.cqrs.command.Command.<StartCommand>builder()
                                                                                    .body(new StartCommand("agg-cycle"))
                                                                                    .build()),
                                 "infinite listener cycle MUST surface (wrapped) on the dispatch path");
            EventDrainOverflowException                                              overflow = assertInstanceOf(
                                                                                                                 EventDrainOverflowException.class,
                                                                                                                 wrapper.getCause(),
                                                                                                                 "root cause MUST be EventDrainOverflowException");
            assertEquals(5, overflow.maxDepth());
            assertTrue(overflow.depthReached() > overflow.maxDepth());
            // Up to and including depth=5, the loop appended events. The first 5 cycle events
            // are in the outbox; the 6th iteration tripped the ceiling before append.
            int appended = drainAll(storage).size();
            assertEquals(5, appended,
                         "drain ceiling MUST stop BEFORE the offending iteration's append — "
                                 + "got " + appended);
        }
    }

    @Test
    void emptyContext_isCompleteNoOp() {
        // A handler that records nothing must not enter the loop at all — zero iterations,
        // zero append calls, zero dispatch calls.
        EventCounter counter = new EventCounter();
        try (FlowRuntime runtime = FlowRuntime.builder()
                .handler(counter)
                .handler(new AbstractNoReturnCommandHandler<StartCommand>() {
                    @Override
                    public void handle(StartCommand cmd) {
                        // intentionally records nothing
                    }
                })
                .build()) {
            runtime.commands().dispatch(
                                        net.nexus_flow.core.cqrs.command.Command.<StartCommand>builder()
                                                .body(new StartCommand("agg-noop"))
                                                .build());
            assertEquals(0, counter.calls.get(),
                         "no events emitted ⇒ no listener invocations");
        }
    }

    @Test
    void invalidDepthCeiling_isRejectedAtBuildTime() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                                   () -> FlowRuntime.builder().eventDrainMaxDepth(0));
        assertTrue(ex.getMessage().contains("eventDrainMaxDepth"));
    }

    @Test
    void defaultDepthCeiling_isExposedOnRuntime() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            assertEquals(FlowRuntime.DEFAULT_EVENT_DRAIN_MAX_DEPTH, runtime.eventDrainMaxDepth());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * Enumerate every appended row including {@code sequenceNo=0}. Note: {@code
     * findSinceSequence} filters strictly greater-than, so {@code -1} is the right sentinel
     * for "everything from the beginning". {@code FAILED_TERMINAL} rows are excluded by the
     * storage's replay contract, which is fine — these tests never put rows there.
     */
    private static List<OutboxRecord> drainAll(InMemoryOutboxStorage storage) {
        List<OutboxRecord> all = storage.findSinceSequence(-1L, Integer.MAX_VALUE);
        assertNotNull(all);
        return all;
    }
}

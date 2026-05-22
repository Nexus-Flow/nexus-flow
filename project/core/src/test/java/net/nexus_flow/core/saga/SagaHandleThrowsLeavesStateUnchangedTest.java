package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.eventsourcing.EventEnvelope;
import net.nexus_flow.core.eventsourcing.EventStore;
import net.nexus_flow.core.eventsourcing.InMemoryEventStore;
import net.nexus_flow.core.eventsourcing.StreamId;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxPayloadCodec;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Resilience invariant: when {@link Saga#handle(EventEnvelope, SagaState)} throws an unexpected
 * {@link RuntimeException}, the {@link SagaRunner} must propagate the failure <em>without</em>
 * saving any half-applied state. A subsequent {@code catchUp()} must see the exact same starting
 * state — guaranteeing at-least-once reprocessing with no silent data loss.
 *
 * <p>This pins the contract callers depend on for "retry the whole batch" semantics in
 * orchestration layers. If the runner ever starts swallowing exceptions or partially-saving state
 * after a failed handler, this test breaks loudly.
 */
class SagaHandleThrowsLeavesStateUnchangedTest {

    static final class Trigger extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Trigger(String aggId) {
            super(aggId);
        }
    }

    static final class Agg extends Aggregate {
        @Serial
        private static final long    serialVersionUID = 1L;
        private final java.util.UUID id               = java.util.UUID.randomUUID();

        @Override
        public java.util.UUID getAggregateId() {
            return id;
        }

        void fire() {
            recordEvent(new Trigger(id.toString()));
        }
    }

    /** Saga whose {@code handle} throws on every invocation — the worst-case handler. */
    static final class BadSaga implements Saga {
        int invocations;

        @Override
        public String type() {
            return "bad-saga";
        }

        @Override
        public Optional<String> correlationKeyFor(EventEnvelope env) {
            return Optional.of(env.payload().getAggregateId());
        }

        @Override
        public SagaTransition handle(EventEnvelope env, SagaState state) {
            invocations++;
            throw new IllegalStateException("synthetic handler failure #" + invocations);
        }
    }

    @Test
    void handlerThrows_runnerPropagatesAndStateRemainsUnsaved() {
        Clock                 clock       = Clock.systemUTC();
        EventStore            eventStore  = new InMemoryEventStore(clock);
        SagaStorage           sagaStorage = new InMemorySagaStorage();
        InMemoryOutboxStorage outbox      = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec       = new JavaSerializationOutboxPayloadCodec();

        Agg agg = new Agg();
        agg.fire();
        StreamId stream = StreamId.of(Agg.class, agg.getAggregateId());
        eventStore.append(stream, 0L, agg.drainEvents());

        BadSaga    saga   = new BadSaga();
        SagaRunner runner = new SagaRunner(eventStore, sagaStorage, saga, outbox, codec, clock);

        // First attempt: handler throws — exception propagates out of catchUp.
        IllegalStateException first =
                assertThrows(IllegalStateException.class, () -> runner.catchUp(ExecutionContext.root()));
        assertTrue(
                   first.getMessage().contains("synthetic handler failure"),
                   "the original handler exception must propagate, not be replaced by an internal one");
        assertEquals(1, saga.invocations, "handler must have been invoked exactly once");

        // No saga state was persisted: storage stays empty for this correlation key.
        Optional<SagaState> persisted = sagaStorage.load("bad-saga", agg.getAggregateId().toString());
        assertTrue(persisted.isEmpty(), "no half-applied state must leak through on a handler failure");

        // No compensation events leaked to the outbox.
        assertEquals(0, outbox.snapshot().size(), "no outbox writes from a failed handle()");

        // Second attempt: still re-attempts the same envelope, proving at-least-once reprocessing.
        assertThrows(IllegalStateException.class, () -> runner.catchUp(ExecutionContext.root()));
        assertEquals(2, saga.invocations, "retry must re-deliver the original envelope to the handler");
    }

    /**
     * Saga that throws on the second envelope only, so we can observe whether the first commit stuck.
     */
    static final class FlakySaga implements Saga {
        int invocations;

        @Override
        public String type() {
            return "flaky-saga";
        }

        @Override
        public Optional<String> correlationKeyFor(EventEnvelope env) {
            return Optional.of(env.payload().getAggregateId());
        }

        @Override
        public SagaTransition handle(EventEnvelope env, SagaState state) {
            invocations++;
            if (invocations == 2) {
                throw new IllegalStateException("blow up on second envelope");
            }
            Map<String, Object> data = new HashMap<>(state.data());
            data.put("seen", invocations);
            return new SagaTransition.Continue(data);
        }
    }

    @Test
    void firstEnvelopeCommits_secondThrows_firstCheckpointIsRetained() {
        Clock                 clock       = Clock.systemUTC();
        EventStore            eventStore  = new InMemoryEventStore(clock);
        SagaStorage           sagaStorage = new InMemorySagaStorage();
        InMemoryOutboxStorage outbox      = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec       = new JavaSerializationOutboxPayloadCodec();

        // Two events on the same correlation key.
        Agg agg = new Agg();
        agg.fire();
        agg.fire();
        StreamId stream = StreamId.of(Agg.class, agg.getAggregateId());
        eventStore.append(stream, 0L, agg.drainEvents());

        FlakySaga  saga   = new FlakySaga();
        SagaRunner runner = new SagaRunner(eventStore, sagaStorage, saga, outbox, codec, clock);

        assertThrows(IllegalStateException.class, () -> runner.catchUp(ExecutionContext.root()));

        // The first envelope's transition WAS saved before the second one blew up — so the saga is
        // checkpointed at position 1, and a retry will only re-deliver the failing second envelope.
        SagaState saved =
                sagaStorage
                        .load("flaky-saga", agg.getAggregateId().toString())
                        .orElseThrow(() -> new AssertionError("first transition must have been committed"));
        assertEquals(1L, saved.lastProcessedGlobalPosition());
        assertEquals(1, saved.data().get("seen"));
    }
}

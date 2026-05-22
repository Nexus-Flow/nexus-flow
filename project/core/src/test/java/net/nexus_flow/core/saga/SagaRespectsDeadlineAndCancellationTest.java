package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.eventsourcing.EventEnvelope;
import net.nexus_flow.core.eventsourcing.EventStore;
import net.nexus_flow.core.eventsourcing.InMemoryEventStore;
import net.nexus_flow.core.eventsourcing.StreamId;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxPayloadCodec;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link SagaRunner#catchUp} consults {@link ExecutionContext#throwIfCancelledOrExpired()}
 * between envelopes; cancellation/deadline expiry surface verbatim and the saga's state past the
 * last successfully-handled envelope remains untouched.
 */
class SagaRespectsDeadlineAndCancellationTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggId) {
            super(aggId);
        }
    }

    static final class Ticker extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private final UUID        id;

        Ticker(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void tick() {
            recordEvent(new Tick(id.toString()));
        }
    }

    static final class TickSaga implements Saga {
        @Override
        public String type() {
            return "tick-saga";
        }

        @Override
        public Optional<String> correlationKeyFor(EventEnvelope env) {
            return env.payload() instanceof Tick t ? Optional.of(t.getAggregateId()) : Optional.empty();
        }

        @Override
        public SagaTransition handle(EventEnvelope env, SagaState state) {
            int                 count = (int) state.data().getOrDefault("count", 0);
            Map<String, Object> next  = new HashMap<>(state.data());
            next.put("count", count + 1);
            return new SagaTransition.Continue(next);
        }
    }

    private static ExecutionContext ctxWith(CancellationToken token, Instant deadline) {
        return new ExecutionContext(
                MessageId.random(),
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                null,
                null,
                deadline,
                token,
                Map.of());
    }

    @Test
    void cancelled_midRun_propagatesException() {
        Clock                 clock       = Clock.systemUTC();
        EventStore            store       = new InMemoryEventStore(clock);
        SagaStorage           sagaStorage = new InMemorySagaStorage();
        InMemoryOutboxStorage outbox      = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec       = new JavaSerializationOutboxPayloadCodec();

        UUID   aggId = UUID.randomUUID();
        Ticker t     = new Ticker(aggId);
        for (int i = 0; i < 5; i++)
            t.tick();
        store.append(StreamId.of(Ticker.class, aggId), 0L, t.drainEvents());

        CancellationToken token = CancellationToken.create();
        token.cancel(); // already cancelled

        SagaRunner runner = new SagaRunner(store, sagaStorage, new TickSaga(), outbox, codec, clock);

        assertThrows(FlowCancellationException.class, () -> runner.catchUp(ctxWith(token, null)));

        // No saga state persisted — the runner aborted before any
        // envelope landed.
        assertEquals(Optional.empty(), sagaStorage.load("tick-saga", aggId.toString()));
    }

    @Test
    void expired_deadline_propagatesException() {
        Clock                 clock       = Clock.systemUTC();
        EventStore            store       = new InMemoryEventStore(clock);
        SagaStorage           sagaStorage = new InMemorySagaStorage();
        InMemoryOutboxStorage outbox      = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec       = new JavaSerializationOutboxPayloadCodec();

        UUID   aggId = UUID.randomUUID();
        Ticker t     = new Ticker(aggId);
        for (int i = 0; i < 5; i++)
            t.tick();
        store.append(StreamId.of(Ticker.class, aggId), 0L, t.drainEvents());

        Instant    past   = Instant.now().minusSeconds(60);
        SagaRunner runner = new SagaRunner(store, sagaStorage, new TickSaga(), outbox, codec, clock);

        assertThrows(
                     FlowDeadlineExceededException.class,
                     () -> runner.catchUp(ctxWith(CancellationToken.create(), past)));

        assertEquals(Optional.empty(), sagaStorage.load("tick-saga", aggId.toString()));
    }
}

package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.time.Clock;
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
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link Saga} state persists across process restart (simulated by discarding the {@link
 * SagaRunner} but keeping storage/events). The second runner resumes from the checkpoint, never
 * replaying already-observed events.
 */
class SagaSurvivesProcessRestartTest {

    static final class Pinged extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Pinged(String aggId) {
            super(aggId);
        }
    }

    static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private final UUID        id;

        Counter(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void ping() {
            recordEvent(new Pinged(id.toString()));
        }
    }

    /** Saga: count Pinged events; complete after 5. */
    static final class CountingSaga implements Saga {
        @Override
        public String type() {
            return "counting-saga";
        }

        @Override
        public Optional<String> correlationKeyFor(EventEnvelope env) {
            return env.payload() instanceof Pinged p ? Optional.of(p.getAggregateId()) : Optional.empty();
        }

        @Override
        public SagaTransition handle(EventEnvelope env, SagaState state) {
            int count = (int) state.data().getOrDefault("count", 0);
            count++;
            Map<String, Object> next = new HashMap<>(state.data());
            next.put("count", count);
            if (count >= 5) {
                return new SagaTransition.Complete(next);
            }
            return new SagaTransition.Continue(next);
        }
    }

    @Test
    void runner_resumesFrom_checkpoint_afterRestart() {
        Clock                 clock       = Clock.systemUTC();
        EventStore            store       = new InMemoryEventStore(clock);
        SagaStorage           sagaStorage = new InMemorySagaStorage();
        InMemoryOutboxStorage outbox      = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec       = new JavaSerializationOutboxPayloadCodec();

        UUID     aggId  = UUID.randomUUID();
        StreamId stream = StreamId.of(Counter.class, aggId);

        // append 3 events, run first runner.
        Counter c1 = new Counter(aggId);
        c1.ping();
        c1.ping();
        c1.ping();
        store.append(stream, 0L, c1.drainEvents());

        SagaRunner runner1  =
                new SagaRunner(store, sagaStorage, new CountingSaga(), outbox, codec, clock);
        long       handled1 = runner1.catchUp(ExecutionContext.root());
        assertEquals(3L, handled1);

        SagaState midState = sagaStorage.load("counting-saga", aggId.toString()).orElseThrow();
        assertEquals(3, midState.data().get("count"));
        assertEquals(SagaStatus.RUNNING, midState.status());
        long midVersion    = midState.version();
        long midCheckpoint = midState.lastProcessedGlobalPosition();
        assertTrue(midCheckpoint >= 3L);

        // simulate restart — append 3 more events and spin up
        // a brand-new SagaRunner. The new runner must NOT re-process
        // the first 3 envelopes — the checkpoint embedded in SagaState
        // is the source of truth.
        Counter c2 = new Counter(aggId);
        c2.hydrateFromSnapshot(3L);
        c2.ping();
        c2.ping();
        c2.ping();
        store.append(stream, 3L, c2.drainEvents());

        SagaRunner runner2  =
                new SagaRunner(store, sagaStorage, new CountingSaga(), outbox, codec, clock);
        long       handled2 = runner2.catchUp(ExecutionContext.root());
        // The second runner SEES all 6 envelopes through readAll but
        // skips the first 3 (already past the saga's checkpoint). Of
        // the 3 remaining, exactly 2 advance the saga (count goes
        // 3 -> 4 -> 5 = COMPLETED); the 6th envelope arrives after
        // termination and is silently ignored.
        assertEquals(
                     2L, handled2, "the runner must skip already-checkpointed envelopes and stop at COMPLETED");

        SagaState finalState = sagaStorage.load("counting-saga", aggId.toString()).orElseThrow();
        assertEquals(SagaStatus.COMPLETED, finalState.status());
        assertEquals(5, finalState.data().get("count"));
        assertTrue(finalState.version() > midVersion, "version must advance across restarts");
    }
}

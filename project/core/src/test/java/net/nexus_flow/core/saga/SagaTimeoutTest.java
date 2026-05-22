package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.nexus_flow.core.eventsourcing.EventEnvelope;
import net.nexus_flow.core.eventsourcing.InMemoryEventStore;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-saga timeout contract:
 *
 * <ol>
 * <li>{@link SagaState#deadline()} is preserved across {@link SagaState#next} transitions.
 * <li>{@link SagaState#isExpired(Instant)} returns true only for RUNNING sagas past their
 * deadline; never for terminal states (COMPLETED / COMPENSATED / FAILED_TERMINAL).
 * <li>{@link InMemorySagaStorage#findExpired} returns only expired RUNNING sagas, sorted
 * earliest-deadline-first, capped at the requested batch size.
 * <li>{@link SagaRunner#sweepExpiredOnce} transitions expired sagas to FAILED_TERMINAL and
 * returns the count of forced transitions.
 * <li>The {@link Saga#timeout()} default of {@link Optional#empty()} preserves the
 * no-timeout behaviour: sagas without an opt-in timeout never expire.
 * </ol>
 */
class SagaTimeoutTest {

    private static final Clock   FIXED_CLOCK = Clock.fixed(
                                                           Instant.parse("2026-05-28T12:00:00Z"), ZoneOffset.UTC);
    private static final Instant T0          = FIXED_CLOCK.instant();
    private static final Instant T_PLUS_5    = T0.plus(Duration.ofMinutes(5));
    private static final Instant T_PLUS_30   = T0.plus(Duration.ofMinutes(30));

    /** Minimal saga whose timeout is opt-in via the test's Builder. */
    static final class TimingSaga implements Saga {
        private final Optional<Duration> declaredTimeout;

        TimingSaga(@org.jspecify.annotations.Nullable Duration timeout) {
            this.declaredTimeout = Optional.ofNullable(timeout);
        }

        @Override
        public String type() {
            return "TimingSaga";
        }

        @Override
        public Optional<String> correlationKeyFor(EventEnvelope envelope) {
            return Optional.of("any");
        }

        @Override
        public SagaTransition handle(EventEnvelope envelope, SagaState currentState) {
            return new SagaTransition.Continue(currentState.data());
        }

        @Override
        public Optional<Duration> timeout() {
            return declaredTimeout;
        }
    }

    // ─── SagaState.deadline behaviour ───────────────────────────────────────

    @Test
    void freshWithDeadline_recordsDeadline_andNextTransitions_preserveIt() {
        SagaState state = SagaState.freshWithDeadline(SagaId.random(), "T", T0, T_PLUS_30);
        assertEquals(T_PLUS_30, state.deadline());
        SagaState advanced = state.next(Map.of(), SagaStatus.RUNNING, 1L, T_PLUS_5);
        assertEquals(T_PLUS_30, advanced.deadline(),
                     "deadline is immutable across regular transitions");
    }

    @Test
    void freshWithoutDeadline_hasNullDeadline_andIsNeverExpired() {
        SagaState state = SagaState.fresh(SagaId.random(), "T", T0);
        assertNull(state.deadline(),
                   "the no-timeout factory MUST leave deadline null");
        assertFalse(state.isExpired(T_PLUS_30));
        assertFalse(state.isExpired(T0.plus(Duration.ofDays(365))));
    }

    @Test
    void isExpired_returnsTrue_onlyForRunningSagasPastDeadline() {
        SagaState running = SagaState.freshWithDeadline(SagaId.random(), "T", T0, T_PLUS_5);
        assertFalse(running.isExpired(T0),
                    "deadline not yet reached");
        assertTrue(running.isExpired(T_PLUS_30),
                   "RUNNING + past deadline ⇒ expired");

        // Terminal states are never considered expired — they are out of the runner's scope.
        SagaState completed = running.next(Map.of(), SagaStatus.COMPLETED, 0L, T_PLUS_5);
        assertFalse(completed.isExpired(T_PLUS_30),
                    "COMPLETED sagas are NEVER expired");
        SagaState compensated = running.next(Map.of(), SagaStatus.COMPENSATED, 0L, T_PLUS_5);
        assertFalse(compensated.isExpired(T_PLUS_30),
                    "COMPENSATED sagas are NEVER expired");
        SagaState failed = running.next(Map.of(), SagaStatus.FAILED_TERMINAL, 0L, T_PLUS_5);
        assertFalse(failed.isExpired(T_PLUS_30),
                    "FAILED_TERMINAL sagas are NEVER expired");
    }

    @Test
    void withDeadline_returnsCopyWithNewDeadline_originalUnchanged() {
        SagaState state   = SagaState.fresh(SagaId.random(), "T", T0);
        SagaState withDdl = state.withDeadline(T_PLUS_30);
        assertNull(state.deadline());
        assertEquals(T_PLUS_30, withDdl.deadline());
        // Same id / version / status / data.
        assertSame(state.id(), withDdl.id());
        assertEquals(state.version(), withDdl.version());
        assertEquals(state.status(), withDdl.status());
    }

    @Test
    void backCompatConstructor_yieldsNullDeadline() {
        // Pre-Phase-D callers using the 8-arg constructor (no deadline) still compile and run.
        SagaState state = new SagaState(
                SagaId.random(), "T", SagaStatus.RUNNING, 0L, Map.of(), T0, T0, 0L);
        assertNull(state.deadline());
    }

    // ─── InMemorySagaStorage.findExpired ────────────────────────────────────

    @Test
    void findExpired_returnsRunningPastDeadline_inEarliestFirstOrder() {
        InMemorySagaStorage storage = new InMemorySagaStorage();

        // Three sagas with deadlines: one already past, one in the future, one with no deadline.
        // saveSagaWithDeadline pre-stamps the data map with _correlationKey (required by the
        // in-memory backend).
        saveSagaWithDeadline(storage, "S", "early", T0.minus(Duration.ofMinutes(10))); // past
        saveSagaWithDeadline(storage, "S", "later", T0.plus(Duration.ofMinutes(10))); // future
        saveSagaWithDeadline(storage, "S", "much-earlier", T0.minus(Duration.ofHours(1))); // past, even earlier
        saveSagaWithDeadline(storage, "S", "no-deadline", null);                          // never expires

        List<SagaState> expired = storage.findExpired(T0, 10);
        assertEquals(2, expired.size(),
                     "only RUNNING + deadline < now sagas are returned");
        assertEquals("much-earlier", expired.get(0).data().get("_correlationKey"),
                     "earliest deadline first");
        assertEquals("early", expired.get(1).data().get("_correlationKey"));
    }

    @Test
    void findExpired_respectsBatchSize() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        for (int i = 0; i < 10; i++) {
            saveSagaWithDeadline(
                                 storage, "S", "k-" + i, T0.minus(Duration.ofMinutes(60 - i))); // all past
        }
        assertEquals(3, storage.findExpired(T0, 3).size(),
                     "batch size is the upper bound");
    }

    @Test
    void findExpired_rejectsZeroBatchSize() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        assertThrows(IllegalArgumentException.class, () -> storage.findExpired(T0, 0));
        assertThrows(IllegalArgumentException.class, () -> storage.findExpired(T0, -1));
    }

    @Test
    void findExpired_defaultOnUnrelatedStorage_returnsEmpty() {
        SagaStorage stub = new SagaStorage() {
            @Override
            public Optional<SagaState> load(String type, String key) {
                return Optional.empty();
            }

            @Override
            public void save(SagaState state, long expectedVersion) {
                // no-op
            }
        };
        assertTrue(stub.findExpired(T0, 10).isEmpty(),
                   "default impl MUST be empty so legacy backends behave as no-timeout");
    }

    // ─── SagaRunner.sweepExpiredOnce ────────────────────────────────────────

    @Test
    void sweepExpiredOnce_transitionsExpiredSagas_toFailedTerminal() {
        InMemorySagaStorage   storage = new InMemorySagaStorage();
        InMemoryEventStore    store   = new InMemoryEventStore();
        InMemoryOutboxStorage outbox  = new InMemoryOutboxStorage();
        SagaRunnerConfig      config  = SagaRunnerConfig.builder().clock(FIXED_CLOCK).build();
        SagaRunner            runner  = new SagaRunner(store, storage, new TimingSaga(null), outbox, config);

        // Seed two expired sagas + one that's still in the future.
        saveSagaWithDeadline(storage, "TimingSaga", "expired-1",
                             T0.minus(Duration.ofMinutes(5)));
        saveSagaWithDeadline(storage, "TimingSaga", "expired-2",
                             T0.minus(Duration.ofMinutes(15)));
        saveSagaWithDeadline(storage, "TimingSaga", "future",
                             T0.plus(Duration.ofMinutes(30)));

        int forced = runner.sweepExpiredOnce(T0);
        assertEquals(2, forced, "two RUNNING + expired sagas MUST transition");

        SagaState expired1 = storage.load("TimingSaga", "expired-1").orElseThrow();
        SagaState expired2 = storage.load("TimingSaga", "expired-2").orElseThrow();
        SagaState future   = storage.load("TimingSaga", "future").orElseThrow();
        assertEquals(SagaStatus.FAILED_TERMINAL, expired1.status());
        assertEquals(SagaStatus.FAILED_TERMINAL, expired2.status());
        assertEquals(SagaStatus.RUNNING, future.status(),
                     "non-expired saga MUST stay RUNNING");
        assertNotNull(expired1.data().get("_terminationReason"));
        assertTrue(expired1.data().get("_terminationReason").toString().contains("deadline exceeded"));
    }

    @Test
    void sweepExpiredOnce_emptyStorage_returnsZero() {
        InMemorySagaStorage   storage = new InMemorySagaStorage();
        InMemoryEventStore    store   = new InMemoryEventStore();
        InMemoryOutboxStorage outbox  = new InMemoryOutboxStorage();
        SagaRunner            runner  = new SagaRunner(
                store, storage, new TimingSaga(null), outbox,
                SagaRunnerConfig.builder().clock(FIXED_CLOCK).build());
        assertEquals(0, runner.sweepExpiredOnce(T0));
    }

    @Test
    void seedFresh_picksUpSagaTimeout_intoStateDeadline() throws Exception {
        // Drive a fresh saga's seeding via catchUp + an envelope so the runner allocates the
        // initial state with the deadline computed from Saga.timeout().
        InMemorySagaStorage   storage = new InMemorySagaStorage();
        InMemoryEventStore    store   = new InMemoryEventStore();
        InMemoryOutboxStorage outbox  = new InMemoryOutboxStorage();

        // Append a single envelope so the runner sees something to process.
        UUID                                       aggId  = UUID.randomUUID();
        net.nexus_flow.core.eventsourcing.StreamId stream =
                new net.nexus_flow.core.eventsourcing.StreamId("Demo", aggId);
        store.append(stream, 0L,
                     java.util.List.of(new TestEvent(aggId.toString())));

        SagaRunner runner = new SagaRunner(
                store, storage,
                new TimingSaga(Duration.ofMinutes(30)),
                outbox,
                SagaRunnerConfig.builder().clock(FIXED_CLOCK).build());
        runner.catchUp(ExecutionContext.root());

        SagaState state = storage.load("TimingSaga", "any").orElseThrow();
        assertEquals(T0.plus(Duration.ofMinutes(30)), state.deadline(),
                     "Saga.timeout() Duration MUST materialise as createdAt + duration on the state");
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static void saveSagaWithDeadline(
            InMemorySagaStorage storage,
            String type,
            String correlationKey,
            @org.jspecify.annotations.Nullable Instant deadline) {
        SagaId    id    = SagaId.random();
        SagaState fresh = new SagaState(
                id, type, SagaStatus.RUNNING, 0L,
                Map.of("_correlationKey", correlationKey),
                T0, T0, 0L, deadline);
        storage.save(fresh, 0L);
    }

    /** Trivial AbstractDomainEvent for the seedFresh test — no business meaning. */
    static final class TestEvent extends net.nexus_flow.core.ddd.AbstractDomainEvent {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        TestEvent(String aggregateId) {
            super(aggregateId);
        }
    }
}

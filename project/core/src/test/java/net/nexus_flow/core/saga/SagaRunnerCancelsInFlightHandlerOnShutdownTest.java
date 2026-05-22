package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * Pins the invariant that {@link SagaRunner} cooperatively cancels an in-flight saga handler when
 * the caller's {@link ExecutionContext} is cancelled mid-execution.
 *
 * <p>Unlike {@code OutboxWorker} and {@code ScheduledCommandWorker}, {@link SagaRunner} is
 * caller-driven — there is no daemon thread, no {@code shutdown()} method, no runner-owned
 * cancellation token. The runner already polls {@code ctx.throwIfCancelledOrExpired()} between
 * envelopes (see {@code SagaRespectsDeadlineAndCancellationTest}); that mechanism covers
 * cancellation BETWEEN handler invocations.
 *
 * <p>The gap this test pins is INSIDE a long-running {@link Saga#handle(EventEnvelope, SagaState)}
 * — the handler signature accepts only the envelope and state, not the context. Previously the saga
 * handler had no way to poll the caller's cancellation token; if the handler blocked, the runner
 * was stuck. The current implementation invokes {@code saga.handle(...)} under {@link
 * FlowScope#getWithContext} so the handler can poll {@code
 * FlowScope.current().get().throwIfCancelledOrExpired()} at safe points.
 *
 * <p>Cancellation remains cooperative — a handler that polls neither the context nor interruption
 * cannot be force-stopped.
 */
class SagaRunnerCancelsInFlightHandlerOnShutdownTest {

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

    @Test
    void cancellingCallerContextMidHandler_propagatesAndReleasesRunnerThread() throws Exception {
        Clock                 clock       = Clock.systemUTC();
        EventStore            store       = new InMemoryEventStore(clock);
        SagaStorage           sagaStorage = new InMemorySagaStorage();
        InMemoryOutboxStorage outbox      = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec       = new JavaSerializationOutboxPayloadCodec();

        UUID   aggId  = UUID.randomUUID();
        Ticker ticker = new Ticker(aggId);
        ticker.tick();
        store.append(StreamId.of(Ticker.class, aggId), 0L, ticker.drainEvents());

        CountDownLatch             handlerEntered = new CountDownLatch(1);
        AtomicReference<Throwable> observed       = new AtomicReference<>();

        @SuppressWarnings("rawtypes") Saga blockingSaga =
                new Saga() {
                    @Override
                    public String type() {
                        return "blocking-saga";
                    }

                    @SuppressWarnings("unchecked")
                    @Override
                    public Optional<String> correlationKeyFor(EventEnvelope envelope) {
                        return envelope.payload() instanceof Tick tick ? Optional.of(tick.getAggregateId()) : Optional.empty();
                    }

                    @Override
                    public SagaTransition handle(EventEnvelope envelope, SagaState state) {
                        handlerEntered.countDown();
                        ExecutionContext flowCtx = FlowScope.current().orElseThrow();
                        long     deadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                        try {
                            while (System.nanoTime() < deadlineNanos) {
                                flowCtx.throwIfCancelledOrExpired();
                                try {
                                    Thread.sleep(20);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    observed.set(ie);
                                    throw new RuntimeException(ie);
                                }
                            }
                            // Safety exit; assertions catch the regression.
                            return new SagaTransition.Continue(state.data());
                        } catch (FlowCancellationException fce) {
                            observed.set(fce);
                            throw fce;
                        }
                    }
                };

        CancellationToken token = CancellationToken.create();
        ExecutionContext  ctx   =
                new ExecutionContext(
                        MessageId.random(),
                        TraceId.random(),
                        CorrelationId.random(),
                        CausationId.ROOT,
                        null,
                        null,
                        null,
                        token,
                        Map.of());

        SagaRunner runner = new SagaRunner(store, sagaStorage, blockingSaga, outbox, codec, clock);

        Thread runnerThread =
                new Thread(
                        () -> {
                            try {
                                runner.catchUp(ctx);
                            } catch (FlowCancellationException ignored) {
                                // expected — caller cancelled mid-handler
                            }
                        },
                        "test-saga-runner");
        runnerThread.start();

        assertTrue(
                   handlerEntered.await(5, TimeUnit.SECONDS),
                   "saga handler did not enter within 5s — scenario broken");

        long t0 = System.nanoTime();
        token.cancel();
        runnerThread.join(2_000L);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertFalse(
                    runnerThread.isAlive(),
                    "saga runner thread still alive 2s after token cancellation — handler did not observe"
                            + " cancellation");
        assertTrue(elapsedMs < 2_000L, "runner shutdown took " + elapsedMs + "ms; expected < 2s");

        Throwable seen = observed.get();
        assertNotNull(
                      seen,
                      "saga handler did not observe cancellation or interruption — FlowScope binding regressed");
        assertTrue(
                   seen instanceof FlowCancellationException || seen instanceof InterruptedException,
                   "saga handler observed unexpected throwable type: " + seen.getClass().getName());

        // Existing saga behaviour intact: state was NOT persisted, because
        // the handler exited via exception (state.save was never reached).
        assertTrue(
                   sagaStorage.load("blocking-saga", aggId.toString()).isEmpty(),
                   "saga state must not be persisted when handler aborts via cancellation");
    }
}

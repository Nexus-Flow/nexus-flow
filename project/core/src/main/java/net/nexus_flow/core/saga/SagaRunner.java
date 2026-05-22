package net.nexus_flow.core.saga;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.nexus_flow.core.eventsourcing.EventEnvelope;
import net.nexus_flow.core.eventsourcing.EventStore;
import net.nexus_flow.core.eventsourcing.EventStream;
import net.nexus_flow.core.outbox.OutboxAppender;
import net.nexus_flow.core.outbox.OutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxStorage;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import org.jspecify.annotations.Nullable;

/**
 * Drives a {@link Saga} against an {@link EventStore}, persisting state through {@link SagaStorage}
 * and routing compensation events through an {@link OutboxStorage}.
 *
 * <p><strong>Saga orchestration:</strong> A saga (process manager) is a long-running state machine
 * that observes domain events from an event store, accumulates state across multiple events, and
 * can emit compensating actions when earlier steps need to be rolled back. The runner coordinates:
 *
 * <ul>
 * <li>Reading envelopes from the event store in batches, always starting from global position 1
 * (skipping already-processed envelopes via the per-saga checkpoint — see {@link
 * SagaState#lastProcessedGlobalPosition()})
 * <li>Filtering envelopes by correlation key so each saga instance processes only relevant events
 * <li>Invoking the saga's {@link Saga#handle(EventEnvelope, SagaState)} to produce a {@link
 * SagaTransition}
 * <li>Persisting the new saga state under optimistic concurrency (version checking)
 * <li>Routing compensation events (if any) through the outbox for durable, at-least-once delivery
 * </ul>
 *
 * <p><strong>Lifecycle events:</strong> INFO log entries track saga start, completion, and
 * compensation. Transitions are logged at DEBUG level per-envelope. Compensation attempts are
 * logged as WARN; terminal failures as ERROR.
 *
 * <p><strong>Concurrency model:</strong> The saga state machine transitions must be atomic at the
 * storage layer. A single {@link SagaRunner} instance should run in single-leader mode — at most
 * one runner processes a given saga type at any moment. The optimistic concurrency check ({@code
 * expectedVersion} matching) serves as a last-resort safety net against concurrent writes.
 * Correlation keys are preserved across restarts; the per-saga checkpoint ({@link
 * SagaState#lastProcessedGlobalPosition()}) ensures idempotency — restarted runners resume exactly
 * where they left off, never replaying already-processed envelopes.
 *
 * <p><strong>Cancellation and deadline safety:</strong> {@link ExecutionContext} is consulted
 * before processing every envelope. If the context is canceled or deadline exceeded, the runner
 * stops cleanly without saving stale state; the exception propagates to the caller. The runner is
 * caller-driven (no daemon thread, no {@code shutdown()}); the caller's token is the cancellation
 * primitive. Inside {@link #processEnvelope}, the saga's {@link Saga#handle} is invoked under
 * {@link FlowScope#getWithContext} so the handler can additionally poll {@code
 * FlowScope.current().get().throwIfCancelledOrExpired()} at safe points within a long-running
 * transition. Cancellation remains cooperative.
 */
public final class SagaRunner {

    private static final Logger LOGGER = System.getLogger(SagaRunner.class.getName());

    /**
     * Default read batch size for {@link EventStore#readAll}. Mirrored from {@link
     * SagaRunnerConfig#DEFAULT_BATCH_SIZE} for backwards-compatible references.
     */
    public static final long DEFAULT_BATCH_SIZE = SagaRunnerConfig.DEFAULT_BATCH_SIZE;

    private final EventStore       eventStore;
    private final SagaStorage      sagaStorage;
    private final Saga             saga;
    private final OutboxStorage    outboxStorage;
    private final SagaRunnerConfig config;

    /**
     * Preferred constructor — bundles all tuning in a single {@link SagaRunnerConfig}.
     *
     * @param eventStore    the event store to read envelopes from; must not be {@code null}
     * @param sagaStorage   durable backing store for saga state; must not be {@code null}
     * @param saga          the saga implementation to drive; must not be {@code null}
     * @param outboxStorage outbox for compensation events; must not be {@code null}
     * @param config        tuning configuration; must not be {@code null}
     */
    public SagaRunner(
            EventStore eventStore,
            SagaStorage sagaStorage,
            Saga saga,
            OutboxStorage outboxStorage,
            SagaRunnerConfig config) {
        this.eventStore    = Objects.requireNonNull(eventStore, "eventStore");
        this.sagaStorage   = Objects.requireNonNull(sagaStorage, "sagaStorage");
        this.saga          = Objects.requireNonNull(saga, "saga");
        this.outboxStorage = Objects.requireNonNull(outboxStorage, "outboxStorage");
        this.config        = Objects.requireNonNull(config, "config");
    }

    /**
     * Backwards-compatible convenience constructor that bundles {@code clock} and {@code codec} into
     * a {@link SagaRunnerConfig} with the default batch size.
     *
     * @param eventStore    the event store to read envelopes from; must not be {@code null}
     * @param sagaStorage   durable backing store for saga state; must not be {@code null}
     * @param saga          the saga implementation to drive; must not be {@code null}
     * @param outboxStorage outbox for compensation events; must not be {@code null}
     * @param codec         optional payload codec for compensation events; {@code null} uses empty-payload
     *                      contract
     * @param clock         time source; must not be {@code null}
     */
    public SagaRunner(
            EventStore eventStore,
            SagaStorage sagaStorage,
            Saga saga,
            OutboxStorage outboxStorage,
            @Nullable OutboxPayloadCodec codec,
            Clock clock) {
        this(
             eventStore,
             sagaStorage,
             saga,
             outboxStorage,
             SagaRunnerConfig.builder().clock(clock).codec(codec).build());
    }

    /**
     * Backwards-compatible convenience constructor with explicit batch size.
     *
     * @param eventStore    the event store to read envelopes from; must not be {@code null}
     * @param sagaStorage   durable backing store for saga state; must not be {@code null}
     * @param saga          the saga implementation to drive; must not be {@code null}
     * @param outboxStorage outbox for compensation events; must not be {@code null}
     * @param codec         optional payload codec for compensation events; {@code null} uses empty-payload
     *                      contract
     * @param clock         time source; must not be {@code null}
     * @param batchSize     number of envelopes to read per {@link EventStore#readAll} call; must be
     *                      {@code >= 1}
     * @throws IllegalArgumentException if {@code batchSize < 1}
     */
    public SagaRunner(
            EventStore eventStore,
            SagaStorage sagaStorage,
            Saga saga,
            OutboxStorage outboxStorage,
            @Nullable OutboxPayloadCodec codec,
            Clock clock,
            long batchSize) {
        this(
             eventStore,
             sagaStorage,
             saga,
             outboxStorage,
             SagaRunnerConfig.builder().clock(clock).codec(codec).batchSize(batchSize).build());
    }

    /**
     * Process every envelope visible in the event store from global position 1 onwards, for this
     * {@link Saga#type()}. Returns the number of envelopes that the saga actually handled (i.e. those
     * for which {@link Saga#correlationKeyFor(EventEnvelope)} returned a value).
     *
     * <p><strong>Checkpoint-based idempotency:</strong> Each saga instance persists its
     * last-processed global position in {@link SagaState#lastProcessedGlobalPosition()}. This runner
     * always reads from global position 1 — it does <em>not</em> seek to a stored checkpoint in the
     * event store. Instead, idempotency is enforced per envelope: any envelope whose position is at
     * or below the persisted checkpoint is silently skipped, so already-processed envelopes are never
     * re-executed. This keeps {@link SagaStorage} simple (no minimum-checkpoint query is needed) at
     * the cost of re-reading older events on restart.
     *
     * <p><strong>Correlation key filtering:</strong> Not every envelope is relevant to every saga
     * instance. The saga's {@link Saga#correlationKeyFor(EventEnvelope)} filters envelopes; if it
     * returns {@link Optional#empty()}, the envelope is skipped.
     *
     * <p><strong>Single-shot compensation:</strong> If a saga returns {@link
     * SagaTransition.Compensate}, the compensation events are routed through the outbox in a single
     * durable batch, and the saga status transitions directly to {@link SagaStatus#COMPENSATED}
     * (one-phase compensation, not multi-round).
     *
     * <p>Life-cycle logging: INFO for saga start/completion/compensation; DEBUG per-transition; WARN
     * when compensations are triggered; ERROR for terminal failures.
     *
     * @return the number of envelopes the saga handled (non-zero if progress was made)
     * @throws FlowCancellationException     if {@code ctx} is canceled mid-run
     * @throws FlowDeadlineExceededException if {@code ctx} expires mid-run
     */
    public long catchUp(ExecutionContext ctx) {
        return catchUp(ctx, config.startGlobalPosition());
    }

    /**
     * Cold-start variant that resumes from an explicit {@code startGlobalPosition} instead of always
     * reading from {@link EventStore#FIRST_GLOBAL_POSITION}.
     *
     * <p>Callers with a durable cross-saga checkpoint (e.g. "every saga of this type is at or beyond
     * position N") can skip the cost of re-reading older envelopes; the per-envelope checkpoint guard
     * inside {@link #processEnvelope} still ensures already-processed envelopes are not re-executed,
     * so passing a {@code startGlobalPosition} that is too low is correct (just slower) and passing
     * one that is too high silently skips never-processed envelopes (data loss). When in doubt, pass
     * {@link EventStore#FIRST_GLOBAL_POSITION} and rely on the per-saga checkpoint to skip work.
     *
     * @param ctx                 caller's execution context; must not be {@code null}
     * @param startGlobalPosition cold-start cursor; must be {@code >= 1}
     * @return number of envelopes the saga handled
     * @throws IllegalArgumentException if {@code startGlobalPosition < 1}
     */
    public long catchUp(ExecutionContext ctx, long startGlobalPosition) {
        Objects.requireNonNull(ctx, "ctx");
        if (startGlobalPosition < EventStore.FIRST_GLOBAL_POSITION) {
            throw new IllegalArgumentException(
                    "startGlobalPosition must be >= "
                            + EventStore.FIRST_GLOBAL_POSITION
                            + ", got "
                            + startGlobalPosition);
        }
        long handled = 0;
        long from    = startGlobalPosition;
        // One carrier for the entire catch-up run: ScopedValue.where(key, value) allocates a Carrier
        // object. Reusing it across all processEnvelope calls avoids one allocation per envelope on
        // the hot path without changing observable semantics (same key, same value throughout).
        ScopedValue.Carrier carrier   = FlowScope.carrierFor(ctx);
        final long          startFrom = from;
        LOGGER.log(
                   Level.INFO,
                   () -> "Starting saga catch-up for type=" + saga.type() + " from globalPosition=" + startFrom);
        while (true) {
            ctx.throwIfCancelledOrExpired();
            EventStream slice = eventStore.readAll(from, config.batchSize());
            if (slice.isEmpty())
                break;
            for (EventEnvelope env : slice.events()) {
                ctx.throwIfCancelledOrExpired();
                handled += processEnvelope(env, ctx, carrier) ? 1 : 0;
            }
            from = slice.lastVersion() + 1L;
            if (slice.size() < config.batchSize())
                break;
        }
        final long finalHandled = handled;
        LOGGER.log(
                   Level.INFO,
                   () -> "Saga catch-up completed for type="
                           + saga.type()
                           + " handled="
                           + finalHandled
                           + " envelopes");
        return handled;
    }

    /**
     * @return {@code true} iff the envelope was relevant to the saga (correlation key matched).
     */
    private boolean processEnvelope(
            EventEnvelope envelope, ExecutionContext ctx, ScopedValue.Carrier carrier) {
        Optional<String> corrOpt = saga.correlationKeyFor(envelope);
        if (corrOpt.isEmpty()) {
            return false;
        }
        String    correlationKey = corrOpt.get();
        SagaState state          =
                sagaStorage.load(saga.type(), correlationKey).orElseGet(() -> seedFresh(correlationKey));

        if (state.status().isTerminal()) {
            LOGGER.log(
                       Level.DEBUG,
                       () -> "Envelope after terminal: type="
                               + saga.type()
                               + " correlationKey="
                               + correlationKey
                               + " status="
                               + state.status()
                               + " position="
                               + envelope.globalPosition());
            return false;
        }
        if (envelope.globalPosition() <= state.lastProcessedGlobalPosition()) {
            LOGGER.log(
                       Level.DEBUG,
                       () -> "Envelope already processed: type="
                               + saga.type()
                               + " correlationKey="
                               + correlationKey
                               + " position="
                               + envelope.globalPosition()
                               + " checkpoint="
                               + state.lastProcessedGlobalPosition());
            return false;
        }

        LOGGER.log(
                   Level.DEBUG,
                   () -> "Processing envelope: type="
                           + saga.type()
                           + " correlationKey="
                           + correlationKey
                           + " position="
                           + envelope.globalPosition());
        // Bind the caller's ExecutionContext so the saga's handle method can poll
        // FlowScope.current().get().throwIfCancelledOrExpired() at safe points
        // inside a long-running transition. The runner's between-envelope poll
        // (catchUp + processEnvelope entry) is the per-envelope guard; this
        // binding is the per-handler one. Cancellation remains cooperative —
        // a handler that polls neither nor blocks on interruptible code cannot
        // be force-stopped.
        SagaTransition transition = carrier.call(() -> saga.handle(envelope, state));
        SagaState      next       = applyTransition(state, transition, envelope, ctx);
        sagaStorage.save(next, state.version());
        return true;
    }

    /**
     * Create a fresh in-memory {@link SagaState} for a new saga instance identified by {@code
     * correlationKey}. The correlation key is embedded in the data map under {@code
     * "_correlationKey"} so that {@link InMemorySagaStorage} can slot the state correctly on save.
     */
    private SagaState seedFresh(String correlationKey) {
        Instant now = config.clock().instant();
        // Compute the deadline at instantiation time from the saga's declared timeout(). The
        // deadline travels with the state through every transition and is the input to the
        // sweeper (sweepExpiredOnce). null means "no timeout" — the saga runs until it
        // reaches a terminal status on its own.
        Instant deadline = saga.timeout()
                .map(d -> now.plus(d))
                .orElse(null);
        return new SagaState(
                SagaId.random(),
                saga.type(),
                SagaStatus.RUNNING,
                0L,
                Map.of("_correlationKey", correlationKey),
                now,
                now,
                0L,
                deadline);
    }

    /**
     * Single sweep over expired sagas — caller-driven (no internal daemon). Reads up to
     * {@link SagaRunnerConfig#timeoutSweepBatchSize()} expired sagas from {@link
     * SagaStorage#findExpired} and transitions each to {@link SagaStatus#FAILED_TERMINAL}
     * with a "deadline exceeded" reason recorded in the saga's data map under {@code
     * "_terminationReason"}.
     *
     * <p>The transition is persisted under the same optimistic-concurrency guarantee as a
     * regular {@code save} — if another runner has advanced the saga concurrently, the sweep
     * skips that saga (the other runner has already produced a fresh state; the deadline check
     * will rerun on the next sweep against the new state).
     *
     * <p>This method is intentionally caller-driven (no daemon thread, no internal scheduler).
     * Spring / Quarkus / Micronaut adapters wire it into their own scheduled executors; pure
     * Java callers invoke it from a {@link java.util.concurrent.ScheduledExecutorService} they
     * own. The pattern mirrors {@link #catchUp(ExecutionContext)} — the runner is a library,
     * not a service.
     *
     * @param now wall-clock instant against which {@code deadline} is compared; usually
     *            {@code config.clock().instant()}
     * @return the number of sagas this sweep transitioned to {@link SagaStatus#FAILED_TERMINAL}
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public int sweepExpiredOnce(Instant now) {
        Objects.requireNonNull(now, "now");
        java.util.List<SagaState> expired = sagaStorage.findExpired(now, config.timeoutSweepBatchSize());
        int                       forced  = 0;
        for (SagaState state : expired) {
            SagaState terminal = state.next(
                                            java.util.Map.of(
                                                             "_correlationKey",
                                                             String.valueOf(state.data().getOrDefault("_correlationKey", "")),
                                                             "_terminationReason",
                                                             "deadline exceeded at " + now),
                                            SagaStatus.FAILED_TERMINAL,
                                            state.lastProcessedGlobalPosition(),
                                            now);
            try {
                sagaStorage.save(terminal, state.version());
                forced++;
                LOGGER.log(
                           Level.WARNING,
                           () -> "saga "
                                   + state.type()
                                   + "/"
                                   + state.data().get("_correlationKey")
                                   + " forced to FAILED_TERMINAL — deadline "
                                   + state.deadline()
                                   + " < now "
                                   + now);
            } catch (SagaConcurrencyException raceLost) {
                // Another writer advanced the saga concurrently — the deadline check will run
                // again on the next sweep against the new state. Safe to skip.
                LOGGER.log(
                           Level.DEBUG,
                           () -> "saga "
                                   + state.type()
                                   + "/"
                                   + state.data().get("_correlationKey")
                                   + " sweep skipped — concurrent writer at version "
                                   + state.version());
            }
        }
        return forced;
    }

    private SagaState applyTransition(
            SagaState state, SagaTransition transition, EventEnvelope envelope, ExecutionContext ctx) {
        var  now        = config.clock().instant();
        long checkpoint = envelope.globalPosition();
        return switch (transition) {
            case SagaTransition.Continue(var newData)                           -> {
                LOGGER.log(
                           Level.DEBUG,
                           () -> "Saga continues: type="
                                   + saga.type()
                                   + " id="
                                   + state.id()
                                   + " version="
                                   + state.version()
                                   + " position="
                                   + checkpoint);
                yield state.next(
                                 mergePreservingCorrelation(state, newData), SagaStatus.RUNNING, checkpoint, now);
            }
            case SagaTransition.Complete(var finalData)                         -> {
                LOGGER.log(
                           Level.INFO,
                           () -> "Saga completed: type="
                                   + saga.type()
                                   + " id="
                                   + state.id()
                                   + " version="
                                   + state.version()
                                   + " position="
                                   + checkpoint);
                yield state.next(
                                 mergePreservingCorrelation(state, finalData), SagaStatus.COMPLETED, checkpoint, now);
            }
            case SagaTransition.Compensate(var newData, var compensationEvents) -> {
                LOGGER.log(
                           Level.WARNING,
                           () -> "Saga compensating: type="
                                   + saga.type()
                                   + " id="
                                   + state.id()
                                   + " version="
                                   + state.version()
                                   + " events="
                                   + compensationEvents.size()
                                   + " position="
                                   + checkpoint);
                OutboxAppender.appendDrainedEvents(
                                                   compensationEvents, ctx, outboxStorage, config.clock(), config.codec());
                yield state.next(
                                 mergePreservingCorrelation(state, newData), SagaStatus.COMPENSATED, checkpoint, now);
            }
            case SagaTransition.Fail(_)                                         -> {
                LOGGER.log(
                           Level.ERROR,
                           () -> "Saga failed: type="
                                   + saga.type()
                                   + " id="
                                   + state.id()
                                   + " version="
                                   + state.version()
                                   + " position="
                                   + checkpoint);
                yield state.next(state.data(), SagaStatus.FAILED_TERMINAL, checkpoint, now);
            }
        };
    }

    private static Map<String, Object> mergePreservingCorrelation(
            SagaState prev, Map<String, Object> incoming) {
        Object corr = prev.data().get("_correlationKey");
        // Fast path — incoming carries the same correlation key (or no correlation context at
        // all): the merged map is the incoming map verbatim. Skips the HashMap + Map.copyOf
        // allocations that the previous always-on merge paid.
        if (corr == null || corr.equals(incoming.get("_correlationKey"))) {
            return Map.copyOf(incoming);
        }
        Map<String, Object> merged = new HashMap<>(incoming.size() + 1);
        merged.putAll(incoming);
        merged.put("_correlationKey", corr);
        return Map.copyOf(merged);
    }
}

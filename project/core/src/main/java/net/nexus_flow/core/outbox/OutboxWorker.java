package net.nexus_flow.core.outbox;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nexus_flow.core.cqrs.command.SaturationRejectedException;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.inbox.InboxClaim;
import net.nexus_flow.core.inbox.InboxStorage;
import net.nexus_flow.core.runtime.*;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.jspecify.annotations.Nullable;

/**
 * Daemon worker that drains an {@link OutboxStorage} and re-publishes each row through the
 * runtime's online {@link EventBus}.
 *
 * <h2>Polling loop</h2>
 *
 * <p>The worker runs on a single background platform thread (named {@code nexus-outbox-worker}). On
 * each iteration it calls {@link OutboxStorage#claimBatch} to atomically claim up to {@link
 * OutboxConfig#workerBatchSize()} {@code PENDING} rows and then dispatches them one by one through
 * {@link EventBus#dispatchResult}. If the batch was non-empty the loop immediately starts the next
 * claim; if the batch was empty (nothing to do) the thread sleeps for {@link
 * OutboxConfig#workerPollInterval()} before trying again.
 *
 * <h2>Retry with exponential backoff + jitter</h2>
 *
 * <p>Transient failures are re-scheduled via {@link OutboxStorage#markFailed} with a delay computed
 * by {@link #computeBackoff(int, Duration, Duration)}: the delay starts at {@link
 * OutboxConfig#workerBackoffBase()} (default 100 ms) and doubles on each attempt, capped at {@link
 * OutboxConfig#workerBackoffMax()} (default 30 s), then multiplied by a uniformly distributed
 * jitter factor in {@code [0.8, 1.2]}. After {@link OutboxConfig#workerMaxAttempts()} total
 * attempts the row is moved to {@link OutboxStatus#FAILED_TERMINAL} and removed from the retry
 * cycle permanently.
 *
 * <h2>Inbox deduplication</h2>
 *
 * <p>When {@link OutboxConfig#inbox()} is non-null the worker calls {@code
 * InboxStorage.claimIfNew(messageId, consumerId, now)} <em>before</em> dispatching. A {@link
 * InboxClaim.Duplicate} result skips the dispatch and marks the outbox row published immediately. A
 * {@link InboxClaim.Fresh} result proceeds with dispatch and resolves the inbox claim through
 * {@code markProcessed} / {@code markFailed} depending on the outcome.
 *
 * <h2>drainOnShutdown</h2>
 *
 * <p>When {@link OutboxConfig#drainOnShutdown()} is {@code true}, calling {@link #shutdown()} (or
 * {@link #close()}) first drains every eligible row through {@link #drainOnce()} in a loop of up to
 * 100 cycles before interrupting the daemon thread. The drain runs on the <em>caller</em> thread so
 * that in-progress claim-batches on the daemon thread are not abandoned.
 *
 * <h2>Pause / resume</h2>
 *
 * <p>The worker can be temporarily halted without stopping it via {@link #pause()} / {@link
 * #resume()}. While paused the polling loop still runs but skips {@code claimBatch} and sleeps the
 * normal poll interval. {@code drainOnce()} is not affected by the paused state.
 *
 * <h2>Shutdown</h2>
 *
 * <p>Call {@link #shutdown()} (or use as an {@link AutoCloseable} in a try-with-resources) to stop
 * the worker. After shutdown the worker cannot be restarted.
 */
@PerRuntime
public final class OutboxWorker extends AbstractDaemonWorker {
    private static final Logger LOG    = System.getLogger(OutboxWorker.class.getName());
    private final OutboxConfig  config;
    private final EventBus      eventBus;
    private final ErrorPolicy   policy;
    private final AtomicBoolean paused = new AtomicBoolean(false);

    /**
     * Constructs a new worker. The worker is not started until {@link #start()} is called (unless
     * {@link OutboxConfig#autoStartWorker()} is managed externally).
     *
     * @param config   worker configuration (storage, codec, poll intervals, etc.); must not be {@code
     *     null}
     * @param eventBus the live event bus through which drained events are dispatched; must not be
     *                 {@code null}
     * @param policy   error policy applied when a handler throws; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public OutboxWorker(OutboxConfig config, EventBus eventBus, ErrorPolicy policy) {
        super("nexus-outbox-worker");
        this.config   = Objects.requireNonNull(config, "config");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.policy   = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Drains a single batch on the <em>caller</em> thread without touching the daemon thread.
     *
     * <p>Useful in tests or for the {@link OutboxConfig#drainOnShutdown()} path. Unlike the daemon
     * loop, this method is unaffected by the {@linkplain #pause() paused} state — it always processes
     * the batch regardless.
     *
     * @return the number of records processed in this batch (0 if none were eligible)
     */
    public int drainOnce() {
        return processBatch();
    }

    /**
     * Stops the worker, optionally draining all remaining eligible rows first.
     *
     * <p>If {@link OutboxConfig#drainOnShutdown()} is {@code true}, this method calls {@link
     * #drainOnce()} in a loop on the <em>caller</em> thread (up to {@link
     * OutboxConfig#drainShutdownCycles()} cycles) until no more eligible rows are returned. Only then
     * is the worker-lifetime {@link CancellationToken} cancelled and the daemon thread interrupted
     * and joined with the {@link OutboxConfig#workerShutdownGrace()} timeout.
     *
     * <p><strong>Cancellation is cooperative.</strong> {@link #workerToken} is cancelled before
     * {@link Thread#interrupt()} so any in-flight dispatch whose handler polls {@link
     * ExecutionContext#throwIfCancelledOrExpired()} observes cancellation through its context. The
     * subsequent interrupt is the fallback for handlers blocked on interruptible operations. A
     * handler that polls neither cannot be force-stopped; {@link Thread#join(long)} caps the wait at
     * the configured grace and {@code shutdown} returns anyway.
     *
     * <p>If the worker was not running (already shut down or never started), this method returns
     * immediately without side effects. Safe to call multiple times.
     */
    @Override
    public void shutdown() {
        // Backwards-compatible default: pick the mode from configuration. drainOnShutdown=true →
        // GRACEFUL, drainOnShutdown=false → IMMEDIATE. Callers that want an explicit, config-
        // independent decision use the shutdown(ShutdownMode) overload.
        shutdown(config.drainOnShutdown() ? ShutdownMode.GRACEFUL : ShutdownMode.IMMEDIATE);
    }

    /**
     * Stops the worker with explicit shutdown semantics.
     *
     * <p>See {@link ShutdownMode} for the contract:
     *
     * <ul>
     * <li>{@link ShutdownMode#GRACEFUL} — drain eligible rows BEFORE cancelling the worker token
     * (honours the at-least-once delivery promise). Bounded by {@link
     * OutboxConfig#drainShutdownCycles()} to prevent infinite loops against pathological
     * storages.
     * <li>{@link ShutdownMode#IMMEDIATE} — skip the drain. Pending rows stay PENDING for the next
     * startup or another replica to re-claim. Suitable for emergency stops.
     * </ul>
     *
     * <p>Idempotent: subsequent calls after the first are no-ops, regardless of the mode argument.
     *
     * <p>The {@link OutboxConfig#workerShutdownGrace()} grace bounds the {@link Thread#join(long)}
     * wait in BOTH modes — the mode only changes what happens BEFORE that wait.
     *
     * @param mode the shutdown semantics to apply; must not be {@code null}
     * @throws NullPointerException if {@code mode} is {@code null}
     */
    public void shutdown(ShutdownMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (!tryBeginShutdown())
            return;
        // The drain MUST run before workerToken.cancel() — otherwise the drained batches would each
        // see a pre-cancelled context and refuse delivery. The drain is the "deliver what's pending"
        // contract; cancellation only governs the daemon's in-flight dispatch that the drain cannot
        // reach.
        if (mode == ShutdownMode.GRACEFUL) {
            int cycles = 0;
            try {
                while (cycles++ < config.drainShutdownCycles()) {
                    int processed = processBatch();
                    if (processed == 0)
                        break;
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, () -> "OutboxWorker GRACEFUL drain failed", t);
            }
        }
        cancelInterruptJoin(config.workerShutdownGrace());
    }

    /**
     * Temporarily pauses the polling loop. While paused the daemon thread continues to run but skips
     * {@link OutboxStorage#claimBatch} and sleeps for the configured {@link
     * OutboxConfig#workerPollInterval()} on each iteration.
     *
     * <p>Does not affect {@link #drainOnce()}, which always processes regardless of pause state. Has
     * no effect if the worker has been shut down.
     *
     * @see #resume()
     * @see #isPaused()
     */
    public void pause() {
        paused.set(true);
    }

    /**
     * Resumes the polling loop after a {@link #pause()}. If the worker was not paused this method has
     * no effect.
     *
     * @see #pause()
     */
    public void resume() {
        paused.set(false);
    }

    /**
     * Returns {@code true} if the worker is currently paused via {@link #pause()}.
     *
     * @return {@code true} while the worker is paused
     */
    public boolean isPaused() {
        return paused.get();
    }

    @Override
    protected void runLoop() {
        long sweepEveryN = Math.max(1L, 30_000L / Math.max(1L, config.workerPollInterval().toMillis()));
        long tick        = 0L;
        while (isRunning()) {
            if (paused.get()) {
                sleepPollInterval();
                continue;
            }
            int processed;
            try {
                processed = processBatch();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, () -> "OutboxWorker batch failed unexpectedly", t);
                processed = 0;
            }
            tick++;
            if (tick % sweepEveryN == 0L) {
                runVisibilityTimeoutSweep();
            }
            if (processed == 0 && isRunning()) {
                sleepPollInterval();
            }
        }
    }

    /**
     * Visibility-timeout sweep — recover IN_FLIGHT rows whose claim has been stale longer than
     * the threshold supplied via {@link OutboxConfig#staleClaimVisibilityTimeout()}. Defensive
     * against worker crashes between {@code claimBatch} and {@code markPublished} /
     * {@code markFailed} that would otherwise leave rows stuck forever.
     *
     * <p>The previous implementation derived its own clamp ({@code max(30s, pollInterval × 10)})
     * and ignored the user-configured value — a dead knob. The config's
     * {@code staleClaimVisibilityTimeout} is now the sole source of truth; the builder's
     * compact-constructor validates {@code value >= workerPollInterval} so a slow but progressing
     * worker is never interrupted by its own sweep.
     */
    private void runVisibilityTimeoutSweep() {
        Duration staleAfter = config.staleClaimVisibilityTimeout();
        try {
            int recovered = config.storage().sweepStaleClaims(staleAfter, config.clock().instant());
            if (recovered > 0) {
                final int      finalRecovered  = recovered;
                final Duration finalStaleAfter = staleAfter;
                LOG.log(Level.INFO,
                        () -> "OutboxWorker recovered " + finalRecovered
                                + " stale IN_FLIGHT row(s) (visibility-timeout="
                                + finalStaleAfter + ")");
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING,
                    () -> "OutboxWorker visibility-timeout sweep failed: " + t.getMessage(), t);
        }
    }

    /**
     * Sleeps for the configured poll interval. On {@link InterruptedException}, re-sets the interrupt
     * flag and returns immediately — the {@code while (running.get())} guard in {@link #runLoop()}
     * then decides whether to exit (shutdown path) or continue (spurious wakeup). Returning rather
     * than looping prevents a tight busy-loop when the interrupt flag remains set across iterations.
     */
    private void sleepPollInterval() {
        try {
            //noinspection BusyWait
            Thread.sleep(config.workerPollInterval().toMillis());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private int processBatch() {
        Instant            now = config.clock().instant();
        List<OutboxRecord> batch;
        try {
            batch = config.storage().claimBatch(config.workerBatchSize(), now);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, () -> "OutboxWorker claimBatch failed", t);
            return 0;
        }
        if (batch.isEmpty())
            return 0;
        for (OutboxRecord r : batch) {
            processOne(r);
        }
        return batch.size();
    }

    private void processOne(OutboxRecord r) {
        DomainEvent event;
        try {
            event = resolveCodecFor(r).decode(r.payloadBytes(), r.payloadType());
        } catch (Throwable t) {
            // Codec decode failure was previously sent straight to terminal — that lost rows
            // when the failure was transient (OOM, classloader race, deserialization-filter
            // transient blip). At-least-once requires we RETRY through the backoff schedule;
            // truly permanent failures (corrupt bytes, missing class) will repeat-fail until
            // workerMaxAttempts is reached and classifyAndMark itself promotes to terminal.
            // The cost of N wasted attempts on permanent failures is bounded; the alternative
            // (silent loss of a transient-failure row) is unbounded operational risk.
            classifyAndMark(r, t);
            return;
        }
        // inbox dedupe BEFORE dispatch.
        InboxStorage     inbox      = config.inbox();
        InboxClaim.Fresh freshClaim = null;
        if (inbox != null) {
            String     inboxConsumerId =
                    java.util.Objects.requireNonNull(config.inboxConsumerId(), "inboxConsumerId");
            InboxClaim claim           = inbox.claimIfNew(r.messageId(), inboxConsumerId, config.clock().instant());
            switch (claim) {
                case InboxClaim.Fresh f     -> freshClaim = f;
                case InboxClaim.Duplicate _ -> {
                    // Already processed (or in-flight on another worker / failed).
                    // Skip dispatch and mark the outbox row published - the
                    // delivery happened, this is just a redundant attempt.
                    markPublishedSafely(r);
                    return;
                }
            }
        }
        ExecutionContext     ctx = rebuildContextFor(r);
        DispatchResult<Void> result;
        try {
            result = eventBus.dispatchResult(event, ctx, policy);
        } catch (FlowCancellationException fce) {
            // Cancellation has TWO distinct sources and they require opposite responses:
            //
            //   (a) Shutdown-induced cancellation. The worker-lifetime token has been cancelled
            //       by shutdown(); the in-flight dispatch saw it through the bound context and
            //       threw. The row is healthy — no real failure happened, only the daemon
            //       is going down. Release it back to PENDING WITHOUT incrementing attempts so
            //       the next replica / restart re-claims it on a clean slate.
            //
            //   (b) Business-driven cancellation. The handler explicitly threw
            //       FlowCancellationException for its own reasons (deadline exceeded mid-handler,
            //       a guard predicate aborting work) WITHOUT the worker token being cancelled.
            //       This IS a real failure attempt — burn it through classifyAndMark so the
            //       backoff schedule and FAILED_TERMINAL transition stay accurate.
            //
            // Discriminator: workerToken.isCancellationRequested(). Cheap, atomic, and unambiguous.
            resolveInboxOnFailure(freshClaim, fce);
            if (workerToken.isCancellationRequested()) {
                releaseToReadySafely(r);
            } else {
                classifyAndMark(r, fce);
            }
            return;
        } catch (Throwable t) {
            // Generic failure path. Most arrivals here are real failures (handler exception, codec
            // failure inside dispatch, …) and get classifyAndMark.
            //
            // The exception is shutdown raising a non-FlowCancellation throwable on its way out —
            // notably, when the handler is blocked on Thread.sleep / lock.lockInterruptibly() and
            // wakes via thread.interrupt() carrying an InterruptedException wrapped in a
            // RuntimeException. Symptomatically indistinguishable from a real failure, but the worker
            // token tells us the trigger was shutdown. Release without burning an attempt.
            resolveInboxOnFailure(freshClaim, t);
            if (workerToken.isCancellationRequested()) {
                releaseToReadySafely(r);
            } else {
                classifyAndMark(r, t);
            }
            return;
        }
        switch (result) {
            case DispatchResult.Success<Void> _        -> {
                resolveInboxOnSuccess(freshClaim);
                markPublishedSafely(r);
            }
            case DispatchResult.Failure<Void> f        -> {
                // Same shutdown-vs-business discriminator as the catch above: the dispatcher may
                // surface a cancellation as a DispatchResult.Failure(FlowCancellationException)
                // instead of throwing.
                resolveInboxOnFailure(freshClaim, f.cause());
                if (isShutdownCancellation(f.cause())) {
                    releaseToReadySafely(r);
                } else {
                    classifyAndMark(r, f.cause());
                }
            }
            case DispatchResult.PartialFailure<Void> p -> {
                Throwable cause =
                        p.failures().isEmpty() ? new IllegalStateException("partial failure without causes") : p.failures().getFirst();
                resolveInboxOnFailure(freshClaim, cause);
                if (isShutdownCancellation(cause)) {
                    releaseToReadySafely(r);
                } else {
                    classifyAndMark(r, cause);
                }
            }
            // Accepted should never be observed here: the
            // outbox worker calls into the SYNCHRONOUS event-bus path
            // (no durable hand-off). Treat as success defensively so
            // forward progress is preserved if a custom dispatcher ever
            // returns this; the inbox row remains correctly resolved.
            case DispatchResult.Accepted<Void> _ -> {
                resolveInboxOnSuccess(freshClaim);
                markPublishedSafely(r);
            }
        }
    }

    /**
     * Resolve the codec for the given row. When a {@link OutboxPayloadCodecRegistry} is configured
     * AND the row carries a {@link OutboxRecord#codecId()} the registry is consulted first; an
     * unknown id throws so the row falls through {@link #processOne}'s {@code catch} into {@link
     * #terminal} (a row stranded by an unregistered codec is a hard failure that requires operator
     * intervention — silently routing it through the wrong codec would deserialize garbage). Rows
     * without a {@code codecId} (legacy / codec-less append) always use {@link OutboxConfig#codec()}
     * — the primary single-codec path.
     */
    private OutboxPayloadCodec resolveCodecFor(OutboxRecord r) {
        OutboxPayloadCodecRegistry registry   = config.codecRegistry();
        String                     rowCodecId = r.codecId();
        if (registry != null && rowCodecId != null) {
            return registry
                    .get(rowCodecId)
                    .orElseThrow(
                                 () -> new OutboxCodecException(
                                         "no codec registered for row codecId='"
                                                 + rowCodecId
                                                 + "' (outboxId="
                                                 + r.outboxId()
                                                 + ", payloadType="
                                                 + r.payloadType().getName()
                                                 + "); add the matching codec to OutboxConfig.codecRegistry() and"
                                                 + " manually replay"));
        }
        return config.codec();
    }

    /**
     * Returns {@code true} iff the worker-lifetime cancellation token has been cancelled, i.e. the
     * worker is shutting down. Used to discriminate shutdown-induced failures from genuine delivery
     * failures across the throw-path and the {@link DispatchResult.Failure} / {@link
     * DispatchResult.PartialFailure} paths so we release the row back to PENDING (without burning an
     * attempt) instead of incrementing the retry counter on what is effectively a no-op attempt.
     *
     * <p>The cause type does NOT discriminate — handlers can surface shutdown cancellation via {@link
     * FlowCancellationException}, a wrapped {@link InterruptedException}, or some custom runtime
     * exception thrown when their blocking operation aborts. The worker token is the only unambiguous
     * signal that the trigger was shutdown. The {@code cause} parameter is preserved on the method
     * signature for symmetry with the throw-path branches and to make telemetry hooks (planned for
     * adapter modules) trivial to add later.
     *
     * <p>Race-window note: a genuine business failure that happens to occur during shutdown will also
     * be treated as shutdown-induced and released to PENDING. This is intentionally permissive: the
     * at-least-once contract holds because the row stays {@code PENDING} and the next replica /
     * restart will re-attempt it; nothing is lost. The alternative (counting it as an attempt) would
     * inflate the attempt counter for what was effectively an aborted attempt, eventually pushing
     * legitimate rows to {@code FAILED_TERMINAL} purely from shutdown noise.
     */
    @SuppressWarnings("unused")
    private boolean isShutdownCancellation(@Nullable Throwable cause) {
        return workerToken.isCancellationRequested();
    }

    private void resolveInboxOnSuccess(InboxClaim.@Nullable Fresh fresh) {
        InboxStorage inbox = config.inbox();
        if (fresh == null || inbox == null)
            return;
        try {
            inbox.markProcessed(fresh.id(), config.clock().instant());
        } catch (RuntimeException re) {
            LOG.log(Level.WARNING, () -> "inbox markProcessed failed for " + fresh.id(), re);
        }
    }

    private void resolveInboxOnFailure(
            InboxClaim.@Nullable Fresh fresh, @org.jspecify.annotations.Nullable Throwable cause) {
        InboxStorage inbox = config.inbox();
        if (fresh == null || inbox == null)
            return;
        try {
            inbox.markFailed(
                             fresh.id(), cause == null ? null : cause.getMessage(), config.clock().instant());
        } catch (RuntimeException re) {
            LOG.log(Level.WARNING, () -> "inbox markFailed failed for " + fresh.id(), re);
        }
    }

    private void markPublishedSafely(OutboxRecord r) {
        try {
            config.storage().markPublished(r.outboxId());
        } catch (IllegalOutboxTransitionException _) {
            // Already terminal - benign.
        }
    }

    private void releaseToReadySafely(OutboxRecord r) {
        try {
            config.storage().releaseToReady(r.outboxId());
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, () -> "OutboxWorker releaseToReady failed for " + r.outboxId(), ex);
        }
    }

    private void classifyAndMark(OutboxRecord r, Throwable cause) {
        int     attemptsAfterTransition = r.attempts() + 1;
        boolean exhausted               = attemptsAfterTransition >= config.workerMaxAttempts();
        if (exhausted) {
            terminal(r, cause);
            return;
        }
        Duration backoff     =
                computeBackoff(
                               attemptsAfterTransition, config.workerBackoffBase(), config.workerBackoffMax());
        Instant  nextRetryAt = config.clock().instant().plus(backoff);
        try {
            config.storage().markFailed(r.outboxId(), cause, nextRetryAt);
        } catch (IllegalOutboxTransitionException _) {
            // Already terminal - benign.
        }
    }

    /**
     * Current dead-letter handler — sourced from {@link OutboxConfig#deadLetterHandler()}.
     * Exposed for diagnostics; production code wires the handler via the config builder.
     */
    public DeadLetterHandler deadLetterHandler() {
        return config.deadLetterHandler();
    }

    private void terminal(OutboxRecord r, Throwable cause) {
        OutboxRecord beforeTransition = r;
        try {
            config.storage().markFailedTerminal(r.outboxId(), cause);
        } catch (IllegalOutboxTransitionException _) {
            // Already terminal - benign. Do NOT invoke the handler again — the previous
            // transition already fired it.
            return;
        }
        // Storage transition succeeded — fire the handler. The handler runs synchronously
        // on the worker thread; we catch every exception so a misbehaving handler does not
        // poison the worker.
        try {
            config.deadLetterHandler().onTerminalFailure(beforeTransition, cause);
        } catch (Throwable handlerError) {
            LOG.log(Level.WARNING,
                    () -> "OutboxWorker dead-letter handler threw on row "
                            + beforeTransition.outboxId()
                            + ": " + handlerError.getMessage(),
                    handlerError);
        }
    }

    /**
     * Computes the retry delay for a given attempt count using truncated exponential backoff with
     * uniform jitter.
     *
     * <p>The formula is:
     *
     * <pre>
     * delay = clamp(base * 2^(attempts-1), 0, max) * jitter
     * </pre>
     *
     * where {@code jitter} is drawn uniformly from {@code [0.8, 1.2]}.
     *
     * <p>The shift exponent is capped at 30 as a JVM-bit-width overflow guard, NOT a tuning knob.
     * {@code Math.min(capMs, baseMs << shift)} already plateaus the output at {@code max} once {@code
     * baseMs * 2^(attempts-1)} reaches the cap, so the shift cap only changes behavior in the
     * contrived case of an extremely small {@code base} and extremely large {@code max}; even there,
     * the cap merely accelerates the plateau the user explicitly asked for. To tune the effective
     * retry behavior, change {@code base} / {@code max} (exposed as {@link
     * OutboxConfig#workerBackoffBase()} / {@link OutboxConfig#workerBackoffMax()} on the {@link
     * OutboxConfig.Builder}) — exposing the shift cap itself as a knob would let callers supply
     * values &gt; 62 that produce undefined behavior on {@code long}-typed shifts (the JVM uses only
     * the low 6 bits of the shift count). The minimum returned delay is 1 ms.
     *
     * @param attempts total delivery attempts so far (including the one that just failed); must be
     *                 {@code >= 1} (values {@code <= 0} are treated as 1)
     * @param base     the base backoff duration (typically {@link OutboxConfig#workerBackoffBase()})
     * @param max      the maximum backoff duration (typically {@link OutboxConfig#workerBackoffMax()})
     * @return the computed retry delay; never {@code null} and never negative
     */
    static Duration computeBackoff(int attempts, Duration base, Duration max) {
        int    effectiveAttempts = attempts <= 0 ? 1 : attempts;
        long   baseMs            = base.toMillis();
        long   capMs             = max.toMillis();
        long   shift             = Math.min(effectiveAttempts - 1L, 30L);
        long   expMs             = Math.min(capMs, baseMs << shift);
        double factor            = 0.8d + ThreadLocalRandom.current().nextDouble(0.4d);
        long   jitteredMs        = Math.max(1L, (long) (expMs * factor));
        return Duration.ofMillis(jitteredMs);
    }

    /**
     * Convenience overload using the framework defaults ({@link OutboxConfig#DEFAULT_BACKOFF_BASE}
     * and {@link OutboxConfig#DEFAULT_BACKOFF_MAX}). Preserved for tests and tooling that exercise
     * the algorithm without constructing a full {@link OutboxConfig}.
     *
     * @param attempts total delivery attempts so far; see {@link #computeBackoff(int, Duration,
     *                 Duration)}
     * @return the computed retry delay
     */
    static Duration computeBackoff(int attempts) {
        return computeBackoff(
                              attempts, OutboxConfig.DEFAULT_BACKOFF_BASE, OutboxConfig.DEFAULT_BACKOFF_MAX);
    }

    /**
     * Build the {@link ExecutionContext} carried into the online {@code EventBus} for one drained
     * row. The context carries the worker-lifetime {@link #workerToken} (NOT a fresh per-record
     * token) so {@link #shutdown()} can cooperatively cancel any in-flight dispatch.
     *
     * <p>The persisted tenant is restored from {@link OutboxRecord#tenantId()} so per-tenant tags
     * (metrics, tracing attributes) line up across the original dispatch and any re-dispatch after a
     * crash. The principal is intentionally left {@code null} on replay — {@link SecurityPrincipal}
     * implementations are adapter-specific and not safely serialisable across JVM restarts; adapter
     * modules that need durable principal propagation contribute their own resolver SPI.
     */
    private ExecutionContext rebuildContextFor(OutboxRecord r) {
        return new ExecutionContext(
                r.messageId(),
                r.traceId(),
                r.correlationId(),
                r.causationId(),
                r.tenantId(),
                null,
                null,
                workerToken,
                Map.of("outbox.replay", Boolean.TRUE, "outbox.attempts", r.attempts()));
    }

    /**
     * Returns {@code true} if {@code t} or any cause in its chain is a {@link
     * SaturationRejectedException}.
     *
     * <p>The outbox worker uses this check to decide whether a dispatch failure should be
     * re-scheduled immediately (saturation — the bus is busy) rather than counted as a hard error.
     *
     * @param t the throwable to inspect; {@code null} is safe (returns {@code false})
     * @return {@code true} if any cause in the chain is a {@code SaturationRejectedException}
     */
    static boolean isSaturationRejected(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SaturationRejectedException)
                return true;
            cur = cur.getCause();
        }
        return false;
    }
}

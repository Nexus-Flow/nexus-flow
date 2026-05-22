package net.nexus_flow.core.scheduling;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.runtime.AbstractDaemonWorker;
import net.nexus_flow.core.runtime.ExecutionContext;

/**
 * Daemon worker that polls a {@link ScheduledCommandStorage} and dispatches every due row through
 * the runtime's {@link CommandBus}.
 *
 * <h2>Dispatch loop</h2>
 *
 * <ol>
 * <li>{@link ScheduledCommandStorage#claimDue(int, Instant) claimDue(batchSize, now)} returns
 * rows whose {@code fireAt &lt;= now}.
 * <li>For each row the worker calls {@link CommandBus#dispatch(Command)
 * commandBus.dispatch(command)}.
 * <li>On success the row is marked {@link ScheduledCommandStatus#DISPATCHED}.
 * <li>On failure, if {@code attempt + 1 &lt; maxAttempts} the row is rescheduled with exponential
 * backoff (capped at {@link ScheduledCommandConfig#backoffMax()}); otherwise it is marked
 * {@link ScheduledCommandStatus#FAILED_TERMINAL}.
 * </ol>
 *
 * <h2>Threading model</h2>
 *
 * <p>A single platform daemon thread runs the poll loop; the design is identical to {@code
 * OutboxWorker}. The worker thread is started via {@link #start()} and runs the loop continuously
 * until {@link #shutdown()} is called. {@link #drainOnce()} is exposed as a public method so tests
 * can drive the worker cycle synchronously for determinism without starting a background thread.
 *
 * <p><strong>Concurrency:</strong> the worker thread is the sole consumer of storage rows; only the
 * worker thread calls {@link ScheduledCommandStorage#claimDue(int, Instant)}, {@link
 * ScheduledCommandStorage#markDispatched}, {@link ScheduledCommandStorage#rescheduleAfterFailure},
 * and {@link ScheduledCommandStorage#markFailedTerminal}. Arbitrary threads call {@link
 * ScheduledCommandStorage#schedule(ScheduledCommandRecord)} to enqueue new commands. The storage
 * implementation is responsible for synchronization between these two contexts.
 *
 * <h2>Logging</h2>
 *
 * <p>The worker logs at the following levels (all messages use lazy format strings via lambdas):
 *
 * <ul>
 * <li>INFO — worker startup and shutdown events
 * <li>DEBUG — per-tick batch processing and retry backoff decisions
 * <li>ERROR — unexpected failures (batch-level or per-command)
 * <li>WARN — (deprecated, now ERROR) — final terminal failures after exhausting retries
 * </ul>
 *
 * <h2>Shutdown</h2>
 *
 * <p>Call {@link #shutdown()} (or use the {@link AutoCloseable} {@link #close()} alias) to stop the
 * daemon. The method logs an INFO message, interrupts the worker thread, and waits up to 5 seconds
 * for it to exit cleanly. If the thread does not exit within the timeout, the caller's interrupt
 * status is restored and the shutdown call returns.
 */
public final class ScheduledCommandWorker extends AbstractDaemonWorker {

    private static final Logger LOG = System.getLogger(ScheduledCommandWorker.class.getName());

    private final ScheduledCommandConfig config;
    private final CommandBus             commandBus;

    /**
     * Construct a worker. The worker thread is NOT started until {@link #start()} is called (or until
     * the runtime calls it automatically when {@link ScheduledCommandConfig#autoStartWorker()} is
     * {@code true}).
     *
     * <p>The provided configuration MUST use a pure {@link java.time.Clock}; that is, the clock's
     * {@code instant()} method must not cache results across different calls (which would cause
     * missed dispatch windows).
     *
     * @param config     worker configuration; must not be {@code null}. The clock must be pure and the
     *                   storage must be thread-safe for concurrent access from this worker thread and arbitrary
     *                   scheduler threads.
     * @param commandBus the bus used to dispatch due commands; must not be {@code null}
     */
    public ScheduledCommandWorker(ScheduledCommandConfig config, CommandBus commandBus) {
        super("nexus-scheduled-command-worker");
        this.config     = Objects.requireNonNull(config, "config");
        this.commandBus = Objects.requireNonNull(commandBus, "commandBus");
    }

    /**
     * Execute a single claim-and-dispatch cycle on the caller's thread.
     *
     * <p>Primarily used by tests to drive the worker deterministically without starting a background
     * thread. Each call processes up to {@code batchSize} rows whose {@code fireAt &lt;= now}.
     *
     * @return the number of rows processed in this cycle
     */
    public int drainOnce() {
        return processBatch();
    }

    /**
     * Signal the worker to stop, interrupt the daemon thread, and block until the thread exits (up to
     * {@link ScheduledCommandConfig#workerShutdownGrace()}).
     *
     * <p>Logs an INFO message when the worker stops. Subsequent calls after the first are no-ops.
     *
     * <p><strong>Cancellation is cooperative.</strong> {@link #workerToken} is cancelled before
     * {@link Thread#interrupt()} so any in-flight command handler that polls {@code
     * FlowScope.current().get().throwIfCancelledOrExpired()} observes cancellation through its
     * context. The subsequent interrupt is the fallback for handlers blocked on interruptible
     * operations. A handler that polls neither cannot be force-stopped; the configured shutdown grace
     * caps the wait and {@code shutdown} returns anyway.
     */
    @Override
    public void shutdown() {
        if (!tryBeginShutdown())
            return;
        LOG.log(Level.INFO, "Stopping ScheduledCommandWorker");
        cancelInterruptJoin(config.workerShutdownGrace());
    }

    @Override
    protected void runLoop() {
        while (isRunning()) {
            int processed;
            try {
                processed = processBatch();
            } catch (Throwable t) {
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.log(Level.ERROR, () -> "ScheduledCommandWorker batch failed unexpectedly", t);
                processed = 0;
            }
            if (processed == 0 && isRunning()) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(config.pollInterval().toMillis());
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private int processBatch() {
        Instant                      now = config.clock().instant();
        List<ScheduledCommandRecord> batch;
        try {
            batch = config.storage().claimDue(config.batchSize(), now);
        } catch (Throwable t) {
            LOG.log(Level.ERROR, () -> "ScheduledCommandWorker claimDue failed", t);
            return 0;
        }
        if (batch.isEmpty())
            return 0;
        LOG.log(Level.DEBUG, () -> "ScheduledCommandWorker processing batch of " + batch.size());
        for (ScheduledCommandRecord r : batch) {
            processOne(r);
        }
        return batch.size();
    }

    /**
     * Attempt to dispatch a single scheduled command.
     *
     * <p>The {@link ExecutionContext} is created by {@link ScheduledCommandConfig#contextFactory()},
     * which defaults to {@link ScheduledCommandConfig#DEFAULT_CONTEXT_FACTORY}. The context is bound
     * for the duration of the dispatch via {@link
     * CommandBus#dispatch(net.nexus_flow.core.cqrs.command.Command, ExecutionContext)} so the handler
     * executor and cross-cutting infrastructure observe the correct context without an explicit
     * parameter on the fire-and-forget path.
     *
     * <p>On success, the row is marked {@link ScheduledCommandStatus#DISPATCHED}. On failure:
     *
     * <ul>
     * <li>If {@code attempt + 1 &lt; maxAttempts}, the row is rescheduled with exponential backoff
     * capped at {@link ScheduledCommandConfig#backoffMax()}. A DEBUG log is emitted.
     * <li>If {@code attempt + 1 &gt;= maxAttempts}, the row is marked {@link
     * ScheduledCommandStatus#FAILED_TERMINAL}. A WARNING log is emitted for manual inspection.
     * </ul>
     *
     * @param r the record to dispatch
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void processOne(ScheduledCommandRecord r) {
        Instant now = config.clock().instant();
        try {
            ExecutionContext ctx = buildExecutionContext(r);
            commandBus.dispatch((Command) r.command(), ctx);
            config.storage().markDispatched(r.id(), now);
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            int    nextAttempt = r.attempt() + 1;
            String error       = errorMessage(t);
            if (nextAttempt >= config.maxAttempts()) {
                LOG.log(
                        Level.WARNING,
                        () -> "ScheduledCommand "
                                + r.id()
                                + " failed terminally after "
                                + nextAttempt
                                + " attempts: "
                                + error);
                config.storage().markFailedTerminal(r.id(), error, now);
            } else {
                Duration delay      = computeBackoff(nextAttempt);
                Instant  nextFireAt = now.plus(delay);
                LOG.log(
                        Level.DEBUG,
                        () -> "ScheduledCommand "
                                + r.id()
                                + " retry "
                                + nextAttempt
                                + " after "
                                + delay
                                + ": "
                                + error);
                config.storage().rescheduleAfterFailure(r.id(), nextFireAt, error);
            }
        }
    }

    /**
     * Build the {@link ExecutionContext} carried into the {@link CommandBus#dispatch(Command,
     * ExecutionContext)} call for one due record.
     *
     * <p><strong>Extension point for inherited tracing context.</strong> Today the worker is the root
     * of the command execution: the configured {@link ScheduledDispatchContextFactory} generates a
     * fresh {@link net.nexus_flow.core.runtime.ids.MessageId}, {@link
     * net.nexus_flow.core.runtime.ids.TraceId}, and {@link
     * net.nexus_flow.core.runtime.ids.CorrelationId} per dispatch. The worker-lifetime {@link
     * #workerToken} is always embedded so cooperative shutdown propagates.
     *
     * <p>Future integrations that need to participate in a distributed trace started by an external
     * scheduler (Quartz, cron4j, Kubernetes CronJob, AWS EventBridge, GCP Cloud Scheduler, …) inject
     * a custom factory through {@link
     * ScheduledCommandConfig.Builder#contextFactory(ScheduledDispatchContextFactory)}; the factory
     * implementation extracts the inherited trace identifiers from whatever transport the scheduler
     * uses (e.g. a W3C {@code traceparent} header carried in the record's payload or a cron-job
     * annotation) and threads them into the returned {@link ExecutionContext}.
     *
     * <p>Centralising the build in this single method makes the extension surface easy to find and
     * keeps {@link #processOne(ScheduledCommandRecord)} focused on the dispatch+failure lifecycle.
     *
     * @param record the row about to be dispatched
     * @return the {@link ExecutionContext} to bind during dispatch; never {@code null}
     */
    private ExecutionContext buildExecutionContext(ScheduledCommandRecord record) {
        return config.contextFactory().contextFor(record, workerToken);
    }

    /**
     * Compute the exponential backoff delay for a given attempt number.
     *
     * <p>Formula: {@code delay = min(backoffBase * 2^(attempt - 1), backoffMax)}.
     *
     * <p>The shift exponent is clamped at 30 as a JVM-bit-width overflow guard, NOT a tuning knob. If
     * multiply-overflow is detected the delay is treated as {@code Long.MAX_VALUE} and then clamped
     * to {@code backoffMax}. To change the effective retry timing, tune {@code config.backoffBase()}
     * / {@code config.backoffMax()} — exposing the shift cap as a knob would let callers request
     * shifts &gt; 62 that produce undefined behavior on {@code long}-typed shifts (the JVM uses only
     * the low 6 bits of the shift count).
     *
     * @param attempt the (1-based) attempt number about to be executed
     * @return the delay before the next retry attempt should be scheduled
     */
    private Duration computeBackoff(int attempt) {
        // attempt is 1-based here (this is the about-to-run attempt number).
        long baseMs = config.backoffBase().toMillis();
        long expMs;
        try {
            // 2^(attempt-1) — clamped to avoid overflow on the long shift.
            int shift = Math.min(attempt - 1, 30);
            expMs = Math.multiplyExact(baseMs, 1L << shift);
        } catch (ArithmeticException _) {
            expMs = Long.MAX_VALUE;
        }
        long capMs = config.backoffMax().toMillis();
        return Duration.ofMillis(Math.min(expMs, capMs));
    }

    /**
     * Extract an error message from a throwable for logging and storage.
     *
     * <p>Returns the class name and message (or empty string if message is null) joined with ": ".
     *
     * @param t the throwable
     * @return a human-readable error string
     */
    private static String errorMessage(Throwable t) {
        String msg = t.getMessage();
        // StringBuilder is no faster than `+` for two-arg concat in modern JIT but avoids the
        // creation of an extra intermediate String when `msg == null` (which is the common
        // case for errors like ClassCastException). Two-arg concat is preferred when both
        // operands are guaranteed non-empty; here `msg` may be null so we branch.
        if (msg == null || msg.isEmpty()) {
            return t.getClass().getName();
        }
        return t.getClass().getName() + ": " + msg;
    }
}

package net.nexus_flow.core.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;

/**
 * Immutable configuration value object for a {@link ScheduledCommandWorker}.
 *
 * <p>Mirrors {@code OutboxConfig} but is specialized for scheduled commands. All defaults are
 * conservative and intentionally identical to the outbox defaults so operators have a single mental
 * model.
 *
 * <p><strong>Clock injection and purity:</strong> the {@code clock} is used to obtain the current
 * instant for fire-at comparisons in every worker tick. The clock MUST be pure — its {@code
 * instant()} method must always return a fresh, monotonically-increasing instant; caching {@code
 * Instant.now()} across ticks will cause drift or missed deadlines. For deterministic testing, use
 * a controllable test clock (e.g., {@code ScheduledCommandWorkerTest.TestClock}).
 *
 * <p><strong>Worker threading:</strong> the worker runs on a single dedicated daemon thread started
 * via {@link ScheduledCommandWorker#start()}. The storage implementation is called exclusively from
 * this worker thread, except for {@link ScheduledCommandStorage#schedule(ScheduledCommandRecord)},
 * which may be called from arbitrary threads. Implementations must be thread-safe for concurrent
 * calls from both contexts.
 *
 * <p><strong>Defaults:</strong>
 *
 * <ul>
 * <li>{@code clock} — {@link Clock#systemUTC()}
 * <li>{@code pollInterval} — 200 ms
 * <li>{@code batchSize} — 32
 * <li>{@code maxAttempts} — 5
 * <li>{@code backoffBase} — 100 ms
 * <li>{@code backoffMax} — 30 s
 * <li>{@code autoStartWorker} — {@code false} (keeps unit tests deterministic)
 * </ul>
 *
 * <p>Use {@link #builder(ScheduledCommandStorage)} to construct an instance.
 *
 * @param storage             durable backend; must not be {@code null}. Responsible for durably storing records
 *                            and supporting concurrent read/write access from worker and scheduler threads.
 * @param clock               time source used for {@code fireAt} comparisons and wall-clock timestamps. Must be
 *                            pure (not cache instants across ticks); must not be {@code null}.
 * @param pollInterval        idle sleep duration when {@code claimDue} returns no rows
 * @param batchSize           maximum rows claimed per worker cycle
 * @param maxAttempts         total dispatch attempts before transitioning to {@link
 *                            ScheduledCommandStatus#FAILED_TERMINAL}
 * @param backoffBase         base duration for exponential retry backoff
 * @param backoffMax          upper cap on the exponential backoff
 * @param autoStartWorker     when {@code true} the worker thread is started automatically on {@code
 *     FlowRuntime.start()}
 * @param contextFactory      strategy that creates an {@link ExecutionContext} for each dispatch;
 *                            defaults to {@link #DEFAULT_CONTEXT_FACTORY}
 * @param workerShutdownGrace maximum time the {@link ScheduledCommandWorker#shutdown()} call blocks
 *                            on {@link Thread#join(long)} after cancelling the worker token and interrupting the daemon
 *                            thread; must be positive; default {@link #DEFAULT_SHUTDOWN_GRACE}
 */
public record ScheduledCommandConfig(
                                     ScheduledCommandStorage storage,
                                     Clock clock,
                                     Duration pollInterval,
                                     int batchSize,
                                     int maxAttempts,
                                     Duration backoffBase,
                                     Duration backoffMax,
                                     boolean autoStartWorker,
                                     ScheduledDispatchContextFactory contextFactory,
                                     Duration workerShutdownGrace) {

    /** Default shutdown grace period used when {@link Builder#workerShutdownGrace} is not called. */
    public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(5);

    /** Default poll interval when no due rows are claimed; see {@link Builder#pollInterval}. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(200);

    /** Default batch size for {@code claimDue}; see {@link Builder#batchSize}. */
    public static final int DEFAULT_BATCH_SIZE = 32;

    /**
     * Default total dispatch attempts before {@code FAILED_TERMINAL}; see {@link
     * Builder#maxAttempts}.
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    /** Default exponential-backoff base; see {@link Builder#backoffBase}. */
    public static final Duration DEFAULT_BACKOFF_BASE = Duration.ofMillis(100);

    /** Default exponential-backoff upper cap; see {@link Builder#backoffMax}. */
    public static final Duration DEFAULT_BACKOFF_MAX = Duration.ofSeconds(30);

    /**
     * Default context factory: generates fresh {@link MessageId}, {@link TraceId}, and {@link
     * CorrelationId} per dispatch so every attempt is independently traceable. The worker-lifetime
     * {@link CancellationToken} is embedded so shutdown cancellation propagates cooperatively to the
     * handler.
     */
    public static final ScheduledDispatchContextFactory DEFAULT_CONTEXT_FACTORY =
            (record, token) -> new ExecutionContext(
                    MessageId.random(),
                    TraceId.random(),
                    CorrelationId.random(),
                    CausationId.ROOT,
                    null,
                    null,
                    null,
                    token,
                    Map.of("scheduled.commandId", record.id(), "scheduled.attempt", record.attempt()));

    /**
     * Validates all fields.
     *
     * @throws NullPointerException     if any reference field is {@code null}
     * @throws IllegalArgumentException if any numeric/duration constraint is violated
     */
    public ScheduledCommandConfig {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0: " + batchSize);
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0: " + maxAttempts);
        }
        Objects.requireNonNull(backoffBase, "backoffBase");
        Objects.requireNonNull(backoffMax, "backoffMax");
        if (backoffBase.isNegative() || backoffBase.isZero()) {
            throw new IllegalArgumentException("backoffBase must be positive");
        }
        if (backoffMax.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException("backoffMax must be >= backoffBase");
        }
        Objects.requireNonNull(contextFactory, "contextFactory");
        Objects.requireNonNull(workerShutdownGrace, "workerShutdownGrace");
        if (workerShutdownGrace.isNegative() || workerShutdownGrace.isZero()) {
            throw new IllegalArgumentException(
                    "workerShutdownGrace must be positive: " + workerShutdownGrace);
        }
    }

    /**
     * Create a fluent {@link Builder} pre-seeded with {@code storage} and all other fields set to
     * their defaults.
     *
     * @param storage the durable backend; must not be {@code null}
     * @return a new builder
     */
    public static Builder builder(ScheduledCommandStorage storage) {
        return new Builder(storage);
    }

    /**
     * Fluent builder for {@link ScheduledCommandConfig}.
     *
     * <p>Obtain an instance via {@link ScheduledCommandConfig#builder(ScheduledCommandStorage)}.
     *
     * <p><strong>Usage example:</strong>
     *
     * <pre>{@code
     * ScheduledCommandConfig cfg = ScheduledCommandConfig.builder(storage)
     * .clock(Clock.systemUTC())
     * .pollInterval(Duration.ofMillis(500))
     * .maxAttempts(10)
     * .build();
     * }</pre>
     */
    public static final class Builder {
        private final ScheduledCommandStorage   storage;
        private Clock                           clock               = Clock.systemUTC();
        private Duration                        pollInterval        = DEFAULT_POLL_INTERVAL;
        private int                             batchSize           = DEFAULT_BATCH_SIZE;
        private int                             maxAttempts         = DEFAULT_MAX_ATTEMPTS;
        private Duration                        backoffBase         = DEFAULT_BACKOFF_BASE;
        private Duration                        backoffMax          = DEFAULT_BACKOFF_MAX;
        private boolean                         autoStartWorker;
        private ScheduledDispatchContextFactory contextFactory      = DEFAULT_CONTEXT_FACTORY;
        private Duration                        workerShutdownGrace = DEFAULT_SHUTDOWN_GRACE;

        private Builder(ScheduledCommandStorage storage) {
            this.storage = Objects.requireNonNull(storage, "storage");
        }

        /**
         * Override the time source; useful in tests with a fixed or controllable clock. The clock MUST
         * be pure—its {@code instant()} method must return a fresh, monotonically-increasing instant;
         * never cache {@code Instant.now()} across ticks.
         *
         * @param clock the clock to use; if not called, defaults to {@link Clock#systemUTC()}
         * @return this builder for chaining
         */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Override the idle sleep duration between empty {@code claimDue} cycles.
         *
         * @param d the poll interval; if not called, defaults to {@link #DEFAULT_POLL_INTERVAL}
         * @return this builder for chaining
         */
        public Builder pollInterval(Duration d) {
            this.pollInterval = d;
            return this;
        }

        /**
         * Override the maximum number of rows claimed per worker cycle.
         *
         * @param n the batch size; if not called, defaults to {@link #DEFAULT_BATCH_SIZE}
         * @return this builder for chaining
         */
        public Builder batchSize(int n) {
            this.batchSize = n;
            return this;
        }

        /**
         * Override the maximum number of dispatch attempts before a row is failed terminally.
         *
         * @param n the max attempts; if not called, defaults to {@link #DEFAULT_MAX_ATTEMPTS}
         * @return this builder for chaining
         */
        public Builder maxAttempts(int n) {
            this.maxAttempts = n;
            return this;
        }

        /**
         * Override the base duration for exponential retry backoff. Used as the base-2 exponent: {@code
         * delay = backoffBase * 2^(attempt-1)}.
         *
         * @param d the backoff base; if not called, defaults to {@link #DEFAULT_BACKOFF_BASE}
         * @return this builder for chaining
         */
        public Builder backoffBase(Duration d) {
            this.backoffBase = d;
            return this;
        }

        /**
         * Override the upper cap on the exponential retry backoff. After exponential growth, the
         * backoff duration is clamped to this maximum.
         *
         * @param d the backoff maximum; if not called, defaults to {@link #DEFAULT_BACKOFF_MAX}
         * @return this builder for chaining
         */
        public Builder backoffMax(Duration d) {
            this.backoffMax = d;
            return this;
        }

        /**
         * When {@code true} the worker thread starts automatically on {@code FlowRuntime.start()}. When
         * {@code false} (the default), callers must explicitly call {@link
         * ScheduledCommandWorker#start()}.
         *
         * @param v {@code true} to auto-start the worker; if not called, defaults to {@code false}
         * @return this builder for chaining
         */
        public Builder autoStartWorker(boolean v) {
            this.autoStartWorker = v;
            return this;
        }

        /**
         * Override the factory used to create an {@link ExecutionContext} for each dispatch attempt.
         *
         * <p>The factory receives the {@link ScheduledCommandRecord} and the worker-lifetime {@link
         * CancellationToken}. It must embed the token in the returned context so that {@link
         * ScheduledCommandWorker#shutdown()} propagates cooperatively to handlers that poll {@link
         * ExecutionContext#throwIfCancelledOrExpired()}. Omitting the token breaks shutdown
         * cancellation.
         *
         * <p>If not called, defaults to {@link ScheduledCommandConfig#DEFAULT_CONTEXT_FACTORY} which
         * generates fresh random {@code MessageId}/{@code TraceId}/{@code CorrelationId} per record.
         *
         * @param factory the factory to use; must not be {@code null}
         * @return this builder for chaining
         */
        public Builder contextFactory(ScheduledDispatchContextFactory factory) {
            this.contextFactory = Objects.requireNonNull(factory, "contextFactory");
            return this;
        }

        /**
         * Sets the maximum time {@link ScheduledCommandWorker#shutdown()} blocks on {@link
         * Thread#join(long)} after cancelling the worker token and interrupting the daemon thread.
         * Bounds the worst-case shutdown latency when handlers ignore both cooperative cancellation and
         * {@code interrupt()}.
         *
         * <p>Cancellation remains cooperative: a handler that polls neither the token nor the interrupt
         * flag cannot be force-stopped — this knob only bounds the wait.
         *
         * <p>Default: {@link ScheduledCommandConfig#DEFAULT_SHUTDOWN_GRACE} (5 s).
         *
         * @param grace the shutdown grace period; must be positive
         * @return this builder for chaining
         */
        public Builder workerShutdownGrace(Duration grace) {
            this.workerShutdownGrace = Objects.requireNonNull(grace, "workerShutdownGrace");
            return this;
        }

        /**
         * Build and return the immutable {@link ScheduledCommandConfig}.
         *
         * @return a validated configuration instance
         */
        public ScheduledCommandConfig build() {
            return new ScheduledCommandConfig(
                    storage,
                    clock,
                    pollInterval,
                    batchSize,
                    maxAttempts,
                    backoffBase,
                    backoffMax,
                    autoStartWorker,
                    contextFactory,
                    workerShutdownGrace);
        }
    }
}

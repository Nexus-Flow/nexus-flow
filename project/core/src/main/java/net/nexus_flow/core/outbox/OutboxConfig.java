package net.nexus_flow.core.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import net.nexus_flow.core.inbox.InboxStorage;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.jspecify.annotations.Nullable;

/**
 * Immutable configuration bundle for the transactional outbox subsystem.
 *
 * <p>Bundles the {@link OutboxStorage}, {@link OutboxPayloadCodec}, and all worker tuning
 * parameters so that a {@link net.nexus_flow.core.runtime.FlowRuntime} can wire the outbox as a
 * single optional dependency.
 *
 * <p>Use {@link #builder(OutboxStorage, OutboxPayloadCodec)} to construct an instance via the
 * fluent builder API.
 *
 * @param storage             persistent store for outbox rows; must not be {@code null}
 * @param codec               encoder/decoder for event payloads; must not be {@code null}
 * @param clock               clock used to stamp rows and compute retry windows; defaults to {@link
 *                            Clock#systemUTC()}
 * @param useOutboxFanOut     when {@code true} the runtime routes {@code AsynchronousDurable} dispatch
 *                            through {@link DurableDispatch} instead of the direct event bus
 * @param workerBatchSize     maximum number of {@code PENDING} rows claimed per polling cycle; must be
 *                            {@code > 0}; default {@link #DEFAULT_BATCH_SIZE}
 * @param workerPollInterval  how long the worker sleeps between batches when the storage returns
 *                            zero eligible rows; must be positive; default {@link #DEFAULT_POLL_INTERVAL}
 * @param workerMaxAttempts   total number of delivery attempts (including the initial one) before a
 *                            row is moved to {@link OutboxStatus#FAILED_TERMINAL}; must be {@code > 0}; default {@link
 *                            #DEFAULT_MAX_ATTEMPTS}
 * @param autoStartWorker     when {@code true} the {@link FlowRuntime} automatically calls {@link
 *                            OutboxWorker#start()} during bootstrap
 * @param drainOnShutdown     when {@code true} {@link OutboxWorker#shutdown()} drains every eligible
 *                            row before stopping the daemon thread; default {@code false} (fast stop)
 * @param drainShutdownCycles maximum number of {@link OutboxWorker#drainOnce()} cycles executed
 *                            during drain-on-shutdown; prevents an infinite loop when a pathological storage always
 *                            returns eligible rows; must be {@code >= 1}; default {@link #DEFAULT_DRAIN_SHUTDOWN_CYCLES}
 * @param workerBackoffBase   base duration for the exponential-with-jitter retry backoff applied by
 *                            the worker after a transient failure; must be positive; default {@link #DEFAULT_BACKOFF_BASE}
 * @param workerBackoffMax    upper cap on the exponential retry backoff; must be {@code >= } {@code
 *     workerBackoffBase}  ; default {@link #DEFAULT_BACKOFF_MAX}
 * @param workerShutdownGrace maximum time the {@link OutboxWorker#shutdown()} call blocks on {@link
 *                            Thread#join(long)} after cancelling the worker token and interrupting the daemon thread; must
 *                            be positive; default {@link #DEFAULT_SHUTDOWN_GRACE}
 * @param inbox               optional {@link InboxStorage} for inbox-based deduplication; {@code null} disables
 *                            inbox integration
 * @param inboxConsumerId     logical name of the consumer pipeline used as the deduplication scope in
 *                            {@link InboxStorage#claimIfNew}; defaults to {@code "outbox-worker"} when {@code inbox} is
 *                            {@code null}; required (non-blank) when {@code inbox} is non-null
 * @param codecRegistry       optional {@link OutboxPayloadCodecRegistry} consulted by the worker before
 *                            falling back to {@link #codec()}. When non-null AND the drained row's {@link
 *                            OutboxRecord#codecId()} is also non-null AND the registry returns a codec for that id, the
 *                            worker uses the registry-resolved codec to decode the payload — this is the multi-codec
 *                            routing path used for wire-format migrations and polyglot outboxes. When {@code null} (the
 *                            common single-codec deployment) every row decodes through {@link #codec()} regardless of its
 *                            persisted {@code codecId}
 */
public record OutboxConfig(
                           OutboxStorage storage,
                           OutboxPayloadCodec codec,
                           Clock clock,
                           boolean useOutboxFanOut,
                           int workerBatchSize,
                           Duration workerPollInterval,
                           int workerMaxAttempts,
                           boolean autoStartWorker,
                           boolean drainOnShutdown,
                           int drainShutdownCycles,
                           Duration workerBackoffBase,
                           Duration workerBackoffMax,
                           Duration workerShutdownGrace,
                           @Nullable InboxStorage inbox,
                           @Nullable String inboxConsumerId,
                           @Nullable OutboxPayloadCodecRegistry codecRegistry,
                           Duration staleClaimVisibilityTimeout,
                           DeadLetterHandler deadLetterHandler) {
    public static final int DEFAULT_BATCH_SIZE = 32;
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);
    public static final int DEFAULT_MAX_ATTEMPTS = 10;
    public static final int DEFAULT_DRAIN_SHUTDOWN_CYCLES = 100;
    public static final Duration DEFAULT_BACKOFF_BASE = Duration.ofMillis(100);
    public static final Duration DEFAULT_BACKOFF_MAX = Duration.ofSeconds(30);
    public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(5);
    /**
     * Default visibility-timeout for stuck IN_FLIGHT rows. 30 s is long enough that a slow
     * but still-progressing worker is not interrupted, short enough that a crashed worker's
     * rows return to PENDING within an operationally reasonable window.
     */
    public static final Duration DEFAULT_STALE_CLAIM_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);

    public OutboxConfig(
            OutboxStorage storage,
            OutboxPayloadCodec codec,
            Clock clock,
            boolean useOutboxFanOut,
            int workerBatchSize,
            Duration workerPollInterval,
            int workerMaxAttempts,
            boolean autoStartWorker,
            boolean drainOnShutdown,
            int drainShutdownCycles,
            Duration workerBackoffBase,
            Duration workerBackoffMax,
            Duration workerShutdownGrace,
            InboxStorage inbox,
            String inboxConsumerId,
            @Nullable OutboxPayloadCodecRegistry codecRegistry,
            Duration staleClaimVisibilityTimeout,
            DeadLetterHandler deadLetterHandler) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(workerPollInterval, "workerPollInterval");
        if (workerBatchSize <= 0) {
            throw new IllegalArgumentException("workerBatchSize must be > 0: " + workerBatchSize);
        }
        if (workerPollInterval.isNegative() || workerPollInterval.isZero()) {
            throw new IllegalArgumentException("workerPollInterval must be > 0: " + workerPollInterval);
        }
        if (workerMaxAttempts <= 0) {
            throw new IllegalArgumentException("workerMaxAttempts must be > 0: " + workerMaxAttempts);
        }
        if (drainShutdownCycles < 1) {
            throw new IllegalArgumentException(
                    "drainShutdownCycles must be >= 1: " + drainShutdownCycles);
        }
        Objects.requireNonNull(workerBackoffBase, "workerBackoffBase");
        Objects.requireNonNull(workerBackoffMax, "workerBackoffMax");
        if (workerBackoffBase.isNegative() || workerBackoffBase.isZero()) {
            throw new IllegalArgumentException(
                    "workerBackoffBase must be positive: " + workerBackoffBase);
        }
        if (workerBackoffMax.compareTo(workerBackoffBase) < 0) {
            throw new IllegalArgumentException(
                    "workerBackoffMax must be >= workerBackoffBase: "
                            + workerBackoffMax
                            + " < "
                            + workerBackoffBase);
        }
        Objects.requireNonNull(workerShutdownGrace, "workerShutdownGrace");
        if (workerShutdownGrace.isNegative() || workerShutdownGrace.isZero()) {
            throw new IllegalArgumentException(
                    "workerShutdownGrace must be positive: " + workerShutdownGrace);
        }

        String normalizedInboxConsumerId = inboxConsumerId;
        if (inbox != null) {
            Objects.requireNonNull(normalizedInboxConsumerId, "inboxConsumerId");
            if (normalizedInboxConsumerId.isBlank()) {
                throw new IllegalArgumentException("inboxConsumerId must not be blank");
            }
        } else if (normalizedInboxConsumerId == null) {
            normalizedInboxConsumerId = "outbox-worker";
        }

        Objects.requireNonNull(staleClaimVisibilityTimeout, "staleClaimVisibilityTimeout");
        if (staleClaimVisibilityTimeout.isNegative() || staleClaimVisibilityTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "staleClaimVisibilityTimeout must be positive: " + staleClaimVisibilityTimeout);
        }
        if (staleClaimVisibilityTimeout.compareTo(workerPollInterval) < 0) {
            throw new IllegalArgumentException(
                    "staleClaimVisibilityTimeout must be >= workerPollInterval: "
                            + staleClaimVisibilityTimeout + " < " + workerPollInterval);
        }
        Objects.requireNonNull(deadLetterHandler, "deadLetterHandler");

        this.storage                     = storage;
        this.codec                       = codec;
        this.clock                       = clock;
        this.useOutboxFanOut             = useOutboxFanOut;
        this.workerBatchSize             = workerBatchSize;
        this.workerPollInterval          = workerPollInterval;
        this.workerMaxAttempts           = workerMaxAttempts;
        this.autoStartWorker             = autoStartWorker;
        this.drainOnShutdown             = drainOnShutdown;
        this.drainShutdownCycles         = drainShutdownCycles;
        this.workerBackoffBase           = workerBackoffBase;
        this.workerBackoffMax            = workerBackoffMax;
        this.workerShutdownGrace         = workerShutdownGrace;
        this.inbox                       = inbox;
        this.inboxConsumerId             = normalizedInboxConsumerId;
        this.codecRegistry               = codecRegistry;
        this.staleClaimVisibilityTimeout = staleClaimVisibilityTimeout;
        this.deadLetterHandler           = deadLetterHandler;
        // Silent double-delivery guard: when fan-out is off AND no inbox dedup is wired,
        // a single AsynchronousDurable dispatch may go through BOTH the inline event bus
        // (immediate local listeners) AND the outbox worker's re-publish path (after
        // claim/dispatch). Operators routinely miss this — surface the risk loud and early.
        if (!useOutboxFanOut && inbox == null && autoStartWorker) {
            java.lang.System.getLogger(OutboxConfig.class.getName()).log(
                                                                         java.lang.System.Logger.Level.WARNING,
                                                                         () -> "OutboxConfig: useOutboxFanOut=false AND no InboxStorage wired. The"
                                                                                 + " inline event bus delivers events at command time AND the outbox"
                                                                                 + " worker re-publishes them after claim — producing double delivery"
                                                                                 + " for any local listener. Configure inbox(...) for dedup OR set"
                                                                                 + " useOutboxFanOut(true) so the runtime routes durable async"
                                                                                 + " dispatch exclusively through the outbox.");
        }
    }

    /**
     * Back-compat 15-arg constructor preserving the pre-{@code codecRegistry} arity. Delegates with
     * {@code codecRegistry = null}, default visibility-timeout and {@link DeadLetterHandler#LOG_ONLY}
     * so existing callers (tests, adapters that target the prior signature) keep working without a
     * sweep. New callers should use the canonical constructor or the {@link Builder}.
     */
    public OutboxConfig(
            OutboxStorage storage,
            OutboxPayloadCodec codec,
            Clock clock,
            boolean useOutboxFanOut,
            int workerBatchSize,
            Duration workerPollInterval,
            int workerMaxAttempts,
            boolean autoStartWorker,
            boolean drainOnShutdown,
            int drainShutdownCycles,
            Duration workerBackoffBase,
            Duration workerBackoffMax,
            Duration workerShutdownGrace,
            InboxStorage inbox,
            String inboxConsumerId) {
        this(storage,
             codec,
             clock,
             useOutboxFanOut,
             workerBatchSize,
             workerPollInterval,
             workerMaxAttempts,
             autoStartWorker,
             drainOnShutdown,
             drainShutdownCycles,
             workerBackoffBase,
             workerBackoffMax,
             workerShutdownGrace,
             inbox,
             inboxConsumerId,
             null,
             normalizeStaleClaimTimeout(workerPollInterval),
             DeadLetterHandler.LOG_ONLY);
    }

    /**
     * Back-compat 16-arg constructor preserving the pre-{@code staleClaimVisibilityTimeout +
     * deadLetterHandler} arity. Delegates with the framework defaults so existing callers
     * (tests, adapters that target the prior signature) keep working.
     */
    public OutboxConfig(
            OutboxStorage storage,
            OutboxPayloadCodec codec,
            Clock clock,
            boolean useOutboxFanOut,
            int workerBatchSize,
            Duration workerPollInterval,
            int workerMaxAttempts,
            boolean autoStartWorker,
            boolean drainOnShutdown,
            int drainShutdownCycles,
            Duration workerBackoffBase,
            Duration workerBackoffMax,
            Duration workerShutdownGrace,
            InboxStorage inbox,
            String inboxConsumerId,
            @Nullable OutboxPayloadCodecRegistry codecRegistry) {
        this(storage,
             codec,
             clock,
             useOutboxFanOut,
             workerBatchSize,
             workerPollInterval,
             workerMaxAttempts,
             autoStartWorker,
             drainOnShutdown,
             drainShutdownCycles,
             workerBackoffBase,
             workerBackoffMax,
             workerShutdownGrace,
             inbox,
             inboxConsumerId,
             codecRegistry,
             normalizeStaleClaimTimeout(workerPollInterval),
             DeadLetterHandler.LOG_ONLY);
    }

    /**
     * The visibility-timeout MUST be {@code >=} the poll interval. When operators provide a
     * very short poll, the back-compat constructors clamp the visibility-timeout to the
     * larger of {@link #DEFAULT_STALE_CLAIM_VISIBILITY_TIMEOUT} and {@code poll × 10}.
     */
    private static Duration normalizeStaleClaimTimeout(Duration pollInterval) {
        Duration tenPolls = pollInterval == null ? DEFAULT_STALE_CLAIM_VISIBILITY_TIMEOUT : pollInterval.multipliedBy(10);
        return tenPolls.compareTo(DEFAULT_STALE_CLAIM_VISIBILITY_TIMEOUT) > 0 ? tenPolls : DEFAULT_STALE_CLAIM_VISIBILITY_TIMEOUT;
    }

    public static Builder builder(OutboxStorage storage, OutboxPayloadCodec codec) {
        return new Builder(storage, codec);
    }

    public static final class Builder {
        private final OutboxStorage      storage;
        private final OutboxPayloadCodec codec;
        private Clock                    clock           = Clock.systemUTC();
        private boolean                  useOutboxFanOut;
        private int                      batchSize       = DEFAULT_BATCH_SIZE;
        private Duration                 pollInterval    = DEFAULT_POLL_INTERVAL;
        private int                      maxAttempts     = DEFAULT_MAX_ATTEMPTS;
        private boolean                  autoStartWorker = true;

        /** drain all due rows before stopping the worker. */
        private boolean drainOnShutdown;

        private int      drainShutdownCycles = DEFAULT_DRAIN_SHUTDOWN_CYCLES;
        private Duration workerBackoffBase   = DEFAULT_BACKOFF_BASE;
        private Duration workerBackoffMax    = DEFAULT_BACKOFF_MAX;
        private Duration workerShutdownGrace = DEFAULT_SHUTDOWN_GRACE;

        private @Nullable InboxStorage               inbox;
        private String                               inboxConsumerId             = "outbox-worker";
        private @Nullable OutboxPayloadCodecRegistry codecRegistry;
        private Duration                             staleClaimVisibilityTimeout =
                DEFAULT_STALE_CLAIM_VISIBILITY_TIMEOUT;
        private DeadLetterHandler                    deadLetterHandler           = DeadLetterHandler.LOG_ONLY;

        private Builder(OutboxStorage storage, OutboxPayloadCodec codec) {
            this.storage = Objects.requireNonNull(storage, "storage");
            this.codec   = Objects.requireNonNull(codec, "codec");
        }

        /**
         * Overrides the clock used to stamp rows and compute retry windows.
         *
         * <p>Providing a fixed-instant clock in tests allows deterministic retry scheduling.
         *
         * @param clock the clock to use; must not be {@code null}
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Controls whether {@code AsynchronousDurable} dispatch is routed through {@link
         * DurableDispatch} instead of the direct event bus.
         *
         * @param flag {@code true} to enable fan-out via the outbox
         * @return this builder
         */
        public Builder useOutboxFanOut(boolean flag) {
            this.useOutboxFanOut = flag;
            return this;
        }

        /**
         * Sets the maximum number of {@code PENDING} rows claimed per polling cycle.
         *
         * <p>Larger values reduce polling overhead but increase per-cycle latency. Default: {@link
         * #DEFAULT_BATCH_SIZE} ({@value #DEFAULT_BATCH_SIZE}).
         *
         * @param batchSize maximum rows per batch; must be {@code > 0}
         * @return this builder
         */
        public Builder workerBatchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Sets how long the worker sleeps between batches when the storage returns zero eligible rows.
         *
         * <p>Shorter intervals reduce end-to-end latency at the cost of more storage round-trips.
         * Default: {@link #DEFAULT_POLL_INTERVAL} (100 ms).
         *
         * @param pollInterval sleep duration; must be positive and non-null
         * @return this builder
         */
        public Builder workerPollInterval(Duration pollInterval) {
            this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            return this;
        }

        /**
         * Sets the total number of delivery attempts (including the initial one) before a row is
         * permanently moved to {@link OutboxStatus#FAILED_TERMINAL}.
         *
         * <p>The retry delay between each attempt grows exponentially from {@link #workerBackoffBase}
         * to {@link #workerBackoffMax} with ±20% jitter. Default: {@link #DEFAULT_MAX_ATTEMPTS}
         * ({@value #DEFAULT_MAX_ATTEMPTS}).
         *
         * @param maxAttempts maximum total attempts; must be {@code > 0}
         * @return this builder
         */
        public Builder workerMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Controls whether the {@link OutboxWorker} is automatically started during {@link
         * net.nexus_flow.core.runtime.FlowRuntime} bootstrap.
         *
         * <p>Set to {@code false} in tests that drive the worker manually via {@link
         * OutboxWorker#drainOnce()} or that need precise control over start timing. Default: {@code
         * true}.
         *
         * @param flag {@code true} to auto-start the worker
         * @return this builder
         */
        public Builder autoStartWorker(boolean flag) {
            this.autoStartWorker = flag;
            return this;
        }

        /**
         * When {@code true}, {@link OutboxWorker#shutdown()} drains every eligible row through {@link
         * OutboxWorker#drainOnce()} on the caller thread (up to {@link #drainShutdownCycles} cycles)
         * before interrupting the daemon thread.
         *
         * <p>Default {@code false} preserves the backwards-compatible fast-stop behaviour: the daemon
         * thread is interrupted immediately and any in-flight rows are left for the next startup cycle
         * to re-claim.
         *
         * @param flag {@code true} to enable drain-on-shutdown
         * @return this builder
         */
        public Builder drainOnShutdown(boolean flag) {
            this.drainOnShutdown = flag;
            return this;
        }

        /**
         * Sets the maximum number of {@link OutboxWorker#drainOnce()} cycles executed during
         * drain-on-shutdown. Caps the drain loop to prevent an infinite loop if a pathological storage
         * always returns eligible rows. Only meaningful when {@link #drainOnShutdown(boolean)} is
         * {@code true}.
         *
         * <p>Default: {@link OutboxConfig#DEFAULT_DRAIN_SHUTDOWN_CYCLES} ({@value
         * OutboxConfig#DEFAULT_DRAIN_SHUTDOWN_CYCLES}).
         *
         * @param cycles maximum drain cycles; must be {@code >= 1}
         * @return this builder
         */
        public Builder drainShutdownCycles(int cycles) {
            this.drainShutdownCycles = cycles;
            return this;
        }

        /**
         * Sets the base duration for the exponential-with-jitter retry backoff applied after a
         * transient delivery failure. The first retry is scheduled at roughly this duration; each
         * subsequent retry doubles the wait (capped at {@link #workerBackoffMax(Duration)}) and is then
         * multiplied by a uniform jitter factor in {@code [0.8, 1.2]}.
         *
         * <p>Default: {@link OutboxConfig#DEFAULT_BACKOFF_BASE} (100 ms).
         *
         * @param base the base backoff duration; must be positive
         * @return this builder
         */
        public Builder workerBackoffBase(Duration base) {
            this.workerBackoffBase = Objects.requireNonNull(base, "workerBackoffBase");
            return this;
        }

        /**
         * Sets the upper cap on the exponential retry backoff. Once the doubled backoff exceeds this
         * value the wait is clamped to {@code workerBackoffMax} (and then jittered).
         *
         * <p>Default: {@link OutboxConfig#DEFAULT_BACKOFF_MAX} (30 s).
         *
         * @param max the maximum backoff duration; must be {@code >= workerBackoffBase}
         * @return this builder
         */
        public Builder workerBackoffMax(Duration max) {
            this.workerBackoffMax = Objects.requireNonNull(max, "workerBackoffMax");
            return this;
        }

        /**
         * Sets the maximum time {@link OutboxWorker#shutdown()} blocks on {@link Thread#join(long)}
         * after cancelling the worker token and interrupting the daemon thread. Bounds the worst-case
         * shutdown latency when handlers ignore both cooperative cancellation and {@code interrupt()}.
         *
         * <p>Cancellation remains cooperative: a handler that polls neither the token nor the interrupt
         * flag cannot be force-stopped — this knob only bounds the wait. See the {@code workerToken}
         * Javadoc in {@link OutboxWorker} for the full contract.
         *
         * <p>Default: {@link OutboxConfig#DEFAULT_SHUTDOWN_GRACE} (5 s).
         *
         * @param grace the shutdown grace period; must be positive
         * @return this builder
         */
        public Builder workerShutdownGrace(Duration grace) {
            this.workerShutdownGrace = Objects.requireNonNull(grace, "workerShutdownGrace");
            return this;
        }

        /**
         * Attaches an {@link InboxStorage} for inbox-based deduplication. When non-null, the {@link
         * OutboxWorker} calls {@link InboxStorage#claimIfNew(net.nexus_flow.core.runtime.ids.MessageId,
         * String, java.time.Instant)} before each dispatch to enforce exactly-once processing
         * semantics.
         *
         * <p>Requires {@link #inboxConsumerId(String)} to be set to a non-blank value. Default: {@code
         * null} (no inbox integration).
         *
         * @param inbox the inbox storage to use, or {@code null} to disable inbox integration
         * @return this builder
         */
        public Builder inbox(@Nullable InboxStorage inbox) {
            this.inbox = inbox;
            return this;
        }

        /**
         * Sets the logical consumer pipeline name used as the deduplication scope in {@link
         * InboxStorage#claimIfNew}.
         *
         * <p>Multiple worker instances processing the same outbox should share the same {@code
         * consumerId} to deduplicate across replicas. Default: {@code "outbox-worker"}.
         *
         * @param consumerId non-null, non-blank consumer pipeline identifier
         * @return this builder
         * @throws NullPointerException if {@code consumerId} is {@code null}
         */
        public Builder inboxConsumerId(String consumerId) {
            this.inboxConsumerId = Objects.requireNonNull(consumerId, "consumerId");
            return this;
        }

        /**
         * Sets the {@link OutboxPayloadCodecRegistry} consulted by the worker when a drained row's
         * {@link OutboxRecord#codecId()} is non-null and a matching codec is registered. Pass {@code
         * null} (the default) to disable multi-codec routing — every row decodes through {@link
         * OutboxConfig#codec()} regardless of its persisted codec id.
         *
         * @param registry the registry, or {@code null} to disable multi-codec routing
         * @return this builder
         */
        public Builder codecRegistry(@Nullable OutboxPayloadCodecRegistry registry) {
            this.codecRegistry = registry;
            return this;
        }

        /**
         * Sets the visibility-timeout for stuck IN_FLIGHT rows. The worker periodically
         * sweeps every IN_FLIGHT row whose claim is older than this window back to PENDING
         * (without incrementing the attempt counter — the recovery is treated as a worker
         * liveness event, NOT a delivery failure).
         *
         * <p>Default: {@link OutboxConfig#DEFAULT_STALE_CLAIM_VISIBILITY_TIMEOUT} (30 s).
         * Must be {@code >= workerPollInterval} — values smaller than the poll interval would
         * race the worker's own progress and steal claims it hasn't finished yet.
         *
         * @param timeout the visibility window; must be positive and {@code >= workerPollInterval}
         * @return this builder
         */
        public Builder staleClaimVisibilityTimeout(Duration timeout) {
            this.staleClaimVisibilityTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Sets the {@link DeadLetterHandler} invoked once per FAILED_TERMINAL transition.
         * Production deployments wire this to publish the failed row to a dead-letter Kafka
         * topic, open an operator ticket, or page on-call.
         *
         * <p>Default: {@link DeadLetterHandler#LOG_ONLY} — logs the terminal failure at
         * WARNING so a fresh deployment still surfaces the signal in container logs.
         *
         * @param handler the handler; must not be {@code null}; use
         *                {@link DeadLetterHandler#NO_OP} when tests need to silence the default
         * @return this builder
         */
        public Builder deadLetterHandler(DeadLetterHandler handler) {
            this.deadLetterHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Builds and returns the {@link OutboxConfig}.
         *
         * @return a fully validated {@code OutboxConfig}
         * @throws IllegalArgumentException if any numeric constraint is violated
         * @throws NullPointerException     if required fields are null
         */
        public OutboxConfig build() {
            // Auto-clamp the default visibility timeout to be at least the poll interval —
            // long polls (e.g. 5 min in tests, or a deployment that polls at 1 min) would
            // otherwise hit the canonical constructor's "staleClaimVisibilityTimeout >=
            // workerPollInterval" guard. Callers that explicitly set the timeout get
            // their value through verbatim.
            Duration effectiveTimeout = staleClaimVisibilityTimeout;
            if (effectiveTimeout.equals(DEFAULT_STALE_CLAIM_VISIBILITY_TIMEOUT) && pollInterval.compareTo(effectiveTimeout) > 0) {
                effectiveTimeout = pollInterval;
            }
            return new OutboxConfig(
                    storage,
                    codec,
                    clock,
                    useOutboxFanOut,
                    batchSize,
                    pollInterval,
                    maxAttempts,
                    autoStartWorker,
                    drainOnShutdown,
                    drainShutdownCycles,
                    workerBackoffBase,
                    workerBackoffMax,
                    workerShutdownGrace,
                    inbox,
                    inboxConsumerId,
                    codecRegistry,
                    effectiveTimeout,
                    deadLetterHandler);
        }
    }
}

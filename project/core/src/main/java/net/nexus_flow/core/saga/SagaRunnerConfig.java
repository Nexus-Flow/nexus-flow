package net.nexus_flow.core.saga;

import java.time.Clock;
import java.util.Objects;
import net.nexus_flow.core.eventsourcing.EventStore;
import net.nexus_flow.core.outbox.OutboxPayloadCodec;
import org.jspecify.annotations.Nullable;

/**
 * Immutable tuning configuration for {@link SagaRunner}.
 *
 * <p>Consolidates the previously-separate constructor parameters ({@code clock}, {@code batchSize},
 * {@code codec}) plus the new {@code startGlobalPosition} cold-start cursor into a single record so
 * adapters and IoC integrations have one place to wire the runner from {@code application.yml} /
 * Spring properties / Quarkus build-time configuration.
 *
 * <p>Every field carries a sensible default; callers override only what they need via {@link
 * #builder()}. The {@link #DEFAULTS} instance is shared so identity-equality is meaningful when
 * comparing "did the operator change anything?".
 *
 * <h2>Fields</h2>
 *
 * <ul>
 * <li>{@code batchSize} — envelopes read per {@link EventStore#readAll} call; must be {@code >=
 *       1}; default {@link #DEFAULT_BATCH_SIZE}.
 * <li>{@code startGlobalPosition} — cold-start cursor for {@link
 * SagaRunner#catchUp(net.nexus_flow.core.runtime.ExecutionContext)}. Operators with a durable
 * cross-saga cursor (e.g. a "minimum checkpoint across every saga of this type") can skip
 * re-reading older envelopes by raising this. Must be {@code >=} {@link
 * EventStore#FIRST_GLOBAL_POSITION}; default {@link EventStore#FIRST_GLOBAL_POSITION}.
 * Per-call override available via {@link
 * SagaRunner#catchUp(net.nexus_flow.core.runtime.ExecutionContext, long)}.
 * <li>{@code clock} — time source for {@link SagaState} timestamps; defaults to {@link
 * Clock#systemUTC()}.
 * <li>{@code codec} — optional {@link OutboxPayloadCodec} for compensation events; {@code null}
 * uses the empty-payload contract.
 * </ul>
 *
 * <h2>Backwards compatibility</h2>
 *
 * <p>The existing {@link SagaRunner} constructors that take {@code clock} / {@code batchSize} /
 * {@code codec} as positional arguments are retained as thin wrappers that build a config from
 * their parameters. New code should prefer the {@code (collaborators, SagaRunnerConfig)}
 * constructor for clarity.
 */
public record SagaRunnerConfig(
                               long batchSize,
                               long startGlobalPosition,
                               Clock clock,
                               @Nullable OutboxPayloadCodec codec,
                               int timeoutSweepBatchSize) {

    /** Default envelope batch size for {@link EventStore#readAll}. */
    public static final long DEFAULT_BATCH_SIZE = 256L;

    /**
     * Default batch size for {@link SagaRunner#sweepExpiredOnce}. Sized to drain reasonable
     * timeout backlogs without monopolising a sweep thread on a pathologically deep queue.
     */
    public static final int DEFAULT_TIMEOUT_SWEEP_BATCH_SIZE = 256;

    /** Shared defaults instance — uses {@link Clock#systemUTC()} and no codec. */
    public static final SagaRunnerConfig DEFAULTS =
            new SagaRunnerConfig(
                    DEFAULT_BATCH_SIZE,
                    EventStore.FIRST_GLOBAL_POSITION,
                    Clock.systemUTC(),
                    null,
                    DEFAULT_TIMEOUT_SWEEP_BATCH_SIZE);

    /**
     * Validates all fields.
     *
     * @throws NullPointerException     if {@code clock} is {@code null}
     * @throws IllegalArgumentException if {@code batchSize < 1} or {@code startGlobalPosition <
     *     EventStore.FIRST_GLOBAL_POSITION} or {@code timeoutSweepBatchSize < 1}
     */
    public SagaRunnerConfig {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1: " + batchSize);
        }
        if (startGlobalPosition < EventStore.FIRST_GLOBAL_POSITION) {
            throw new IllegalArgumentException(
                    "startGlobalPosition must be >= "
                            + EventStore.FIRST_GLOBAL_POSITION
                            + ": "
                            + startGlobalPosition);
        }
        if (timeoutSweepBatchSize < 1) {
            throw new IllegalArgumentException(
                    "timeoutSweepBatchSize must be >= 1: " + timeoutSweepBatchSize);
        }
        Objects.requireNonNull(clock, "clock");
    }

    /**
     * Backwards-compatible 4-argument constructor for callers that pre-date the timeout
     * sweeper knob. Delegates to the canonical 5-argument constructor with
     * {@link #DEFAULT_TIMEOUT_SWEEP_BATCH_SIZE}.
     */
    public SagaRunnerConfig(
            long batchSize,
            long startGlobalPosition,
            Clock clock,
            @Nullable OutboxPayloadCodec codec) {
        this(batchSize, startGlobalPosition, clock, codec, DEFAULT_TIMEOUT_SWEEP_BATCH_SIZE);
    }

    /**
     * Fluent builder seeded with {@link #DEFAULTS}.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link SagaRunnerConfig}. */
    public static final class Builder {
        private long                         batchSize             = DEFAULT_BATCH_SIZE;
        private long                         startGlobalPosition   = EventStore.FIRST_GLOBAL_POSITION;
        private Clock                        clock                 = Clock.systemUTC();
        private @Nullable OutboxPayloadCodec codec;
        private int                          timeoutSweepBatchSize = DEFAULT_TIMEOUT_SWEEP_BATCH_SIZE;

        private Builder() {
        }

        /**
         * Override the envelope batch size.
         *
         * @param n batch size; must be {@code >= 1}
         * @return this builder
         */
        public Builder batchSize(long n) {
            this.batchSize = n;
            return this;
        }

        /**
         * Override the cold-start cursor used by {@code catchUp(ctx)} with no second argument.
         *
         * @param position cursor; must be {@code >=} {@link EventStore#FIRST_GLOBAL_POSITION}
         * @return this builder
         */
        public Builder startGlobalPosition(long position) {
            this.startGlobalPosition = position;
            return this;
        }

        /**
         * Override the time source used for {@link SagaState} timestamps.
         *
         * @param clock time source; must not be {@code null}
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Set the optional outbox payload codec for compensation events.
         *
         * @param codec codec; may be {@code null} to use the empty-payload contract
         * @return this builder
         */
        public Builder codec(@Nullable OutboxPayloadCodec codec) {
            this.codec = codec;
            return this;
        }

        /**
         * Override the sweep batch size for {@link SagaRunner#sweepExpiredOnce}. Must be
         * {@code >= 1}. Default {@link #DEFAULT_TIMEOUT_SWEEP_BATCH_SIZE}.
         */
        public Builder timeoutSweepBatchSize(int n) {
            this.timeoutSweepBatchSize = n;
            return this;
        }

        /**
         * Build and return the immutable config.
         *
         * @return a validated {@code SagaRunnerConfig}
         */
        public SagaRunnerConfig build() {
            return new SagaRunnerConfig(
                    batchSize, startGlobalPosition, clock, codec, timeoutSweepBatchSize);
        }
    }
}

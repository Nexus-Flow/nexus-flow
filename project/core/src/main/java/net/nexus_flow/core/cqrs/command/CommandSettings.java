package net.nexus_flow.core.cqrs.command;

import java.time.Duration;
import java.util.Optional;
import net.nexus_flow.core.runtime.ExecutionMode;
import org.jspecify.annotations.Nullable;

/**
 * Per-handler execution configuration.
 *
 * <p>Two orthogonal knobs are exposed:
 *
 * <ul>
 * <li>{@link #executionMode()} — override for the {@code ExecutionStrategyResolver}'s saga /
 * runtime-default fallback chain.
 * <li>{@link #backpressure()} — queue-depth / saturation-policy knob.
 * </ul>
 */
public final class CommandSettings {

    /**
     * Per-handler {@link ExecutionMode} override. When non-{@code null}, {@code
     * ExecutionStrategyResolver} uses this value directly. When {@code null} the resolver falls back
     * to the saga / runtime default precedence.
     */
    private @Nullable ExecutionMode executionMode;

    /**
     * Per-handler back-pressure configuration. {@code null} defers to {@link
     * HandlerBackpressureSettings#DEFAULTS}.
     */
    private @Nullable HandlerBackpressureSettings backpressure;

    /**
     * Per-handler back-off tuning. {@code null} defers to {@link BackoffSettings#defaults()}. Drives
     * the {@link ExponentialBackoffStrategy} the executor builds at construction; runtime mutation of
     * this field after the executor is constructed has no effect on the running strategy.
     */
    private @Nullable BackoffSettings backoff;

    /**
     * Per-handler concurrency cap. {@code null} defers to {@link ConcurrencySettings#defaults()}.
     * Applied once at executor construction as an upper bound on {@link
     * CommandHandler#getConcurrencyLevel()}; runtime mutation has no effect.
     */
    private @Nullable ConcurrencySettings concurrency;

    /**
     * Creates a builder for command settings.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the configured execution mode override.
     *
     * @return configured mode, or empty when the runtime fallback chain should apply
     */
    public Optional<ExecutionMode> executionMode() {
        return Optional.ofNullable(executionMode);
    }

    /**
     * Returns the configured back-pressure settings.
     *
     * @return configured settings, or {@link HandlerBackpressureSettings#DEFAULTS} when unset
     */
    public HandlerBackpressureSettings backpressure() {
        return backpressure != null ? backpressure : HandlerBackpressureSettings.DEFAULTS;
    }

    /**
     * Returns the configured back-off tuning.
     *
     * @return configured settings, or {@link BackoffSettings#defaults()} when unset
     */
    public BackoffSettings backoff() {
        return backoff != null ? backoff : BackoffSettings.defaults();
    }

    /**
     * Returns the configured concurrency cap.
     *
     * @return configured settings, or {@link ConcurrencySettings#defaults()} when unset
     */
    public ConcurrencySettings concurrency() {
        return concurrency != null ? concurrency : ConcurrencySettings.defaults();
    }

    /** Package-private mutator wired through {@link Builder#executionMode(ExecutionMode)}. */
    void executionMode(@Nullable ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    /**
     * Package-private mutator wired through {@link
     * Builder#backpressure(HandlerBackpressureSettings)}.
     */
    void backpressure(@Nullable HandlerBackpressureSettings backpressure) {
        this.backpressure = backpressure;
    }

    /** Package-private mutator wired through {@link Builder#backoff(BackoffSettings)}. */
    void backoff(@Nullable BackoffSettings backoff) {
        this.backoff = backoff;
    }

    /** Package-private mutator wired through {@link Builder#concurrency(ConcurrencySettings)}. */
    void concurrency(@Nullable ConcurrencySettings concurrency) {
        this.concurrency = concurrency;
    }

    /** Fluent builder for {@link CommandSettings}. */
    public static final class Builder {
        private final CommandSettings commandSettings = new CommandSettings();

        /**
         * Sets or clears the handler-level execution mode override.
         *
         * @param executionMode mode override, or {@code null} to restore runtime fallback resolution
         * @return this builder
         */
        public Builder executionMode(@Nullable ExecutionMode executionMode) {
            commandSettings.executionMode(executionMode);
            return this;
        }

        /**
         * Sets or clears the handler-level back-pressure settings.
         *
         * @param backpressure back-pressure settings, or {@code null} to restore defaults
         * @return this builder
         */
        public Builder backpressure(@Nullable HandlerBackpressureSettings backpressure) {
            commandSettings.backpressure(backpressure);
            return this;
        }

        /**
         * Builds back-pressure settings inline and stores them on this builder.
         *
         * @param queueDepth   maximum queued dispatches allowed for the handler
         * @param policy       saturation policy to apply when the queue is full
         * @param blockTimeout timeout used by {@link SaturationPolicy#BLOCK_CALLER}; may be {@code
         *     null}
         * @return this builder
         */
        public Builder backpressure(
                int queueDepth, SaturationPolicy policy, @Nullable Duration blockTimeout) {
            commandSettings.backpressure(
                                         new HandlerBackpressureSettings(queueDepth, policy, blockTimeout));
            return this;
        }

        /**
         * Sets or clears the handler-level back-off tuning.
         *
         * @param backoff back-off settings, or {@code null} to restore {@link
         *                BackoffSettings#defaults()}
         * @return this builder
         */
        public Builder backoff(@Nullable BackoffSettings backoff) {
            commandSettings.backoff(backoff);
            return this;
        }

        /**
         * Sets or clears the handler-level concurrency cap.
         *
         * @param concurrency concurrency settings, or {@code null} to restore {@link
         *                    ConcurrencySettings#defaults()}
         * @return this builder
         */
        public Builder concurrency(@Nullable ConcurrencySettings concurrency) {
            commandSettings.concurrency(concurrency);
            return this;
        }

        /**
         * Returns the configured settings instance.
         *
         * @return configured command settings
         */
        public CommandSettings build() {
            return commandSettings;
        }
    }
}

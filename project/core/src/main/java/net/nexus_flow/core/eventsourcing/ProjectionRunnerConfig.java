package net.nexus_flow.core.eventsourcing;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable tuning configuration for {@link ProjectionRunner}.
 *
 * <p>Consolidates the previously-separate {@code batchSize} / {@code pollInterval} constructor
 * parameters plus the {@code shutdownGrace} cap on {@link ProjectionRunner#close()}'s {@link
 * Thread#join(long)} wait. Single record so adapter modules and IoC integrations have one place to
 * wire the runner from external configuration.
 *
 * <h2>Fields</h2>
 *
 * <ul>
 * <li>{@code batchSize} — envelopes per {@link EventStore#readAll} call; must be {@code >= 1};
 * default {@link #DEFAULT_BATCH_SIZE}.
 * <li>{@code pollInterval} — sleep between catch-up cycles when the head is reached; must be
 * positive; default {@link #DEFAULT_POLL_INTERVAL}.
 * <li>{@code shutdownGrace} — maximum time {@link ProjectionRunner#close()} blocks on {@link
 * Thread#join(long)} after interrupting the daemon thread; must be positive; default {@link
 * #DEFAULT_SHUTDOWN_GRACE}.
 * </ul>
 */
public record ProjectionRunnerConfig(
                                     long batchSize, Duration pollInterval, Duration shutdownGrace) {

    /** Default envelope batch size. */
    public static final long DEFAULT_BATCH_SIZE = 256L;

    /** Default poll interval when the head is reached. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);

    /** Default shutdown grace on {@link ProjectionRunner#close()}. */
    public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(2);

    /** Shared defaults instance. */
    public static final ProjectionRunnerConfig DEFAULTS =
            new ProjectionRunnerConfig(DEFAULT_BATCH_SIZE, DEFAULT_POLL_INTERVAL, DEFAULT_SHUTDOWN_GRACE);

    /**
     * Validates all fields.
     *
     * @throws NullPointerException     if any reference field is {@code null}
     * @throws IllegalArgumentException if {@code batchSize < 1} or any duration is non-positive
     */
    public ProjectionRunnerConfig {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1: " + batchSize);
        }
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be > 0: " + pollInterval);
        }
        Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        if (shutdownGrace.isNegative() || shutdownGrace.isZero()) {
            throw new IllegalArgumentException("shutdownGrace must be > 0: " + shutdownGrace);
        }
    }

    /**
     * Fluent builder seeded with {@link #DEFAULTS}.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link ProjectionRunnerConfig}. */
    public static final class Builder {
        private long     batchSize     = DEFAULT_BATCH_SIZE;
        private Duration pollInterval  = DEFAULT_POLL_INTERVAL;
        private Duration shutdownGrace = DEFAULT_SHUTDOWN_GRACE;

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
         * Override the poll interval.
         *
         * @param d interval; must be positive
         * @return this builder
         */
        public Builder pollInterval(Duration d) {
            this.pollInterval = Objects.requireNonNull(d, "pollInterval");
            return this;
        }

        /**
         * Override the shutdown grace period.
         *
         * @param d grace; must be positive
         * @return this builder
         */
        public Builder shutdownGrace(Duration d) {
            this.shutdownGrace = Objects.requireNonNull(d, "shutdownGrace");
            return this;
        }

        /**
         * Build and return the immutable config.
         *
         * @return a validated {@code ProjectionRunnerConfig}
         */
        public ProjectionRunnerConfig build() {
            return new ProjectionRunnerConfig(batchSize, pollInterval, shutdownGrace);
        }
    }
}

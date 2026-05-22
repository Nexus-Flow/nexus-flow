package net.nexus_flow.core.ring.transport;

import java.time.Duration;
import java.util.Objects;
import net.nexus_flow.core.observability.MetricsRecorder;
import net.nexus_flow.core.ring.wire.RingProtocol;
import org.jspecify.annotations.Nullable;

/**
 * Configuration for {@link RingDialer}. Mirrors {@link RingAcceptorConfig} where the knobs
 * are symmetric and adds dialer-specific tuning.
 *
 * @param connectTimeout            TCP connect timeout
 * @param timeouts                  per-connection budgets (handshake/idle/write/drain) applied
 *                                  to the resulting {@link RingConnection}
 * @param maxBodyBytes              per-frame body cap
 * @param outboundQueueCapacity     bounded outbound queue depth (frames)
 * @param outboundQueueByteCapacity bounded outbound queue depth (bytes)
 * @param tlsConfig                 mTLS configuration; {@code null} for plain TCP (test/dev)
 * @param metrics                   observability sink
 */
public record RingDialerConfig(
                               Duration connectTimeout,
                               ConnectionTimeouts timeouts,
                               int maxBodyBytes,
                               int outboundQueueCapacity,
                               long outboundQueueByteCapacity,
                               @Nullable RingTlsConfig tlsConfig,
                               MetricsRecorder metrics) {

    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 5_000L;
    public static final int DEFAULT_OUTBOUND_QUEUE_CAPACITY = 1024;
    public static final long DEFAULT_OUTBOUND_QUEUE_BYTES = 64L * 1024L * 1024L;

    public RingDialerConfig {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive: " + connectTimeout);
        }
        Objects.requireNonNull(timeouts, "timeouts");
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("maxBodyBytes must be >= 1");
        }
        if (outboundQueueCapacity < 1) {
            throw new IllegalArgumentException("outboundQueueCapacity must be >= 1");
        }
        if (outboundQueueByteCapacity < 1L) {
            throw new IllegalArgumentException("outboundQueueByteCapacity must be >= 1");
        }
        Objects.requireNonNull(metrics, "metrics");
    }

    /** Loopback-test default. */
    public static RingDialerConfig loopbackForTests() {
        return new RingDialerConfig(
                Duration.ofSeconds(2),
                ConnectionTimeouts.loopbackForTests(),
                64 * 1024,
                64,
                32L * 1024L * 1024L,
                null,
                MetricsRecorder.NO_OP);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Duration                connectTimeout            = Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MS);
        private ConnectionTimeouts      timeouts                  = ConnectionTimeouts.defaults();
        private int                     maxBodyBytes              = RingProtocol.DEFAULT_MAX_BODY_BYTES;
        private int                     outboundQueueCapacity     = DEFAULT_OUTBOUND_QUEUE_CAPACITY;
        private long                    outboundQueueByteCapacity = DEFAULT_OUTBOUND_QUEUE_BYTES;
        private @Nullable RingTlsConfig tlsConfig;
        private MetricsRecorder         metrics                   = MetricsRecorder.NO_OP;

        private Builder() {
        }

        public Builder connectTimeout(Duration d) {
            this.connectTimeout = d;
            return this;
        }

        public Builder timeouts(ConnectionTimeouts t) {
            this.timeouts = t;
            return this;
        }

        public Builder maxBodyBytes(int n) {
            this.maxBodyBytes = n;
            return this;
        }

        public Builder outboundQueueCapacity(int n) {
            this.outboundQueueCapacity = n;
            return this;
        }

        public Builder outboundQueueByteCapacity(long n) {
            this.outboundQueueByteCapacity = n;
            return this;
        }

        public Builder tlsConfig(@Nullable RingTlsConfig t) {
            this.tlsConfig = t;
            return this;
        }

        public Builder metrics(MetricsRecorder m) {
            this.metrics = m;
            return this;
        }

        public RingDialerConfig build() {
            return new RingDialerConfig(
                    connectTimeout, timeouts, maxBodyBytes,
                    outboundQueueCapacity, outboundQueueByteCapacity,
                    tlsConfig, metrics);
        }
    }
}

package net.nexus_flow.core.ring.transport;

import java.time.Duration;
import java.util.Objects;
import net.nexus_flow.core.observability.MetricsRecorder;
import net.nexus_flow.core.ring.wire.RingProtocol;
import org.jspecify.annotations.Nullable;

/**
 * Configuration for {@link RingAcceptor}.
 *
 * <p>The previous version of this record declared {@code handshakeTimeout} but the acceptor
 * never read it — a high-severity DoS finding from the audit. The redesigned configuration
 * centralizes every per-connection timeout in {@link ConnectionTimeouts} so the connection
 * state machine can enforce the right SO_TIMEOUT at every phase.
 *
 * @param bindAddress               bind address; default {@link #WILDCARD_BIND_ADDRESS}
 * @param port                      TCP port; {@code 0} for ephemeral
 * @param acceptBacklog             OS listen backlog
 * @param maxConnections            application-layer cap on accepted live connections (Semaphore
 *                                  permits)
 * @param acceptBurst               per-source token-bucket burst capacity
 * @param acceptRefillPerSecond     per-source token-bucket refill rate
 * @param timeouts                  per-connection timeout budgets (handshake/idle/write/drain)
 * @param shutdownGrace             maximum time {@link RingAcceptor#shutdown()} waits for the
 *                                  accept loop to exit
 * @param maxBodyBytes              per-frame body-size cap
 * @param outboundQueueCapacity     per-connection bounded queue depth (frames)
 * @param outboundQueueByteCapacity per-connection bounded queue depth (bytes)
 * @param decodeMemoryBudget        global cap on bytes the decoders may have pre-allocated across
 *                                  all connections; the acceptor refuses new accepts when this
 *                                  cap would be breached
 * @param tlsConfig                 mTLS configuration; {@code null} for plain-TCP test/dev mode
 * @param requireTlsInProduction    when {@code true} (production default), {@link
 *                                  RingAcceptor#start()} refuses to start without TLS
 * @param metrics                   observability sink; defaults to {@link MetricsRecorder#NO_OP}
 */
public record RingAcceptorConfig(
                                 String bindAddress,
                                 int port,
                                 int acceptBacklog,
                                 int maxConnections,
                                 double acceptBurst,
                                 double acceptRefillPerSecond,
                                 ConnectionTimeouts timeouts,
                                 Duration shutdownGrace,
                                 int maxBodyBytes,
                                 int outboundQueueCapacity,
                                 long outboundQueueByteCapacity,
                                 long decodeMemoryBudget,
                                 @Nullable RingTlsConfig tlsConfig,
                                 boolean requireTlsInProduction,
                                 MetricsRecorder metrics) {

    /** Wildcard bind address — listen on every interface. */
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    public static final String WILDCARD_BIND_ADDRESS = "0.0.0.0";

    public static final int DEFAULT_BACKLOG = 128;
    public static final int DEFAULT_MAX_CONNECTIONS = 1024;
    public static final long DEFAULT_SHUTDOWN_GRACE_MS = 5_000L;
    public static final int DEFAULT_OUTBOUND_QUEUE_CAPACITY = 1024;
    public static final long DEFAULT_OUTBOUND_QUEUE_BYTES = 64L * 1024L * 1024L; // 64 MiB
    public static final long DEFAULT_DECODE_MEMORY_BUDGET = 256L * 1024L * 1024L; // 256 MiB
    public static final double DEFAULT_ACCEPT_BURST = 64d;
    public static final double DEFAULT_ACCEPT_REFILL = 16d;

    public RingAcceptorConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (bindAddress.isBlank()) {
            throw new IllegalArgumentException("bindAddress must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (acceptBacklog < 1) {
            throw new IllegalArgumentException("acceptBacklog must be >= 1: " + acceptBacklog);
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be >= 1: " + maxConnections);
        }
        if (acceptBurst <= 0) {
            throw new IllegalArgumentException("acceptBurst must be > 0: " + acceptBurst);
        }
        if (acceptRefillPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "acceptRefillPerSecond must be > 0: " + acceptRefillPerSecond);
        }
        Objects.requireNonNull(timeouts, "timeouts");
        Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        if (shutdownGrace.isNegative() || shutdownGrace.isZero()) {
            throw new IllegalArgumentException("shutdownGrace must be positive: " + shutdownGrace);
        }
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("maxBodyBytes must be >= 1");
        }
        if (outboundQueueCapacity < 1) {
            throw new IllegalArgumentException("outboundQueueCapacity must be >= 1");
        }
        if (outboundQueueByteCapacity < 1L) {
            throw new IllegalArgumentException("outboundQueueByteCapacity must be >= 1");
        }
        if (decodeMemoryBudget < (long) maxBodyBytes) {
            throw new IllegalArgumentException(
                    "decodeMemoryBudget must be >= maxBodyBytes: "
                            + decodeMemoryBudget + " < " + maxBodyBytes);
        }
        Objects.requireNonNull(metrics, "metrics");
    }

    /** Loopback-test defaults: ephemeral port, plain TCP, tight caps. */
    public static RingAcceptorConfig loopbackForTests() {
        return new RingAcceptorConfig(
                PeerAddress.LOOPBACK_HOST,
                0,
                DEFAULT_BACKLOG,
                32,
                DEFAULT_ACCEPT_BURST,
                DEFAULT_ACCEPT_REFILL,
                ConnectionTimeouts.loopbackForTests(),
                Duration.ofSeconds(2),
                64 * 1024,
                64,
                64L * 1024L * 1024L,
                32L * 1024L * 1024L,
                null,
                false,
                MetricsRecorder.NO_OP);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String                  bindAddress               = WILDCARD_BIND_ADDRESS;
        private int                     port;
        private int                     acceptBacklog             = DEFAULT_BACKLOG;
        private int                     maxConnections            = DEFAULT_MAX_CONNECTIONS;
        private double                  acceptBurst               = DEFAULT_ACCEPT_BURST;
        private double                  acceptRefillPerSecond     = DEFAULT_ACCEPT_REFILL;
        private ConnectionTimeouts      timeouts                  = ConnectionTimeouts.defaults();
        private Duration                shutdownGrace             = Duration.ofMillis(DEFAULT_SHUTDOWN_GRACE_MS);
        private int                     maxBodyBytes              = RingProtocol.DEFAULT_MAX_BODY_BYTES;
        private int                     outboundQueueCapacity     = DEFAULT_OUTBOUND_QUEUE_CAPACITY;
        private long                    outboundQueueByteCapacity = DEFAULT_OUTBOUND_QUEUE_BYTES;
        private long                    decodeMemoryBudget        = DEFAULT_DECODE_MEMORY_BUDGET;
        private @Nullable RingTlsConfig tlsConfig;
        private boolean                 requireTlsInProduction    = true;
        private MetricsRecorder         metrics                   = MetricsRecorder.NO_OP;

        private Builder() {
        }

        public Builder bindAddress(String a) {
            this.bindAddress = a;
            return this;
        }

        public Builder port(int p) {
            this.port = p;
            return this;
        }

        public Builder acceptBacklog(int n) {
            this.acceptBacklog = n;
            return this;
        }

        public Builder maxConnections(int n) {
            this.maxConnections = n;
            return this;
        }

        public Builder acceptBurst(double b) {
            this.acceptBurst = b;
            return this;
        }

        public Builder acceptRefillPerSecond(double r) {
            this.acceptRefillPerSecond = r;
            return this;
        }

        public Builder timeouts(ConnectionTimeouts t) {
            this.timeouts = t;
            return this;
        }

        public Builder shutdownGrace(Duration g) {
            this.shutdownGrace = g;
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

        public Builder decodeMemoryBudget(long n) {
            this.decodeMemoryBudget = n;
            return this;
        }

        public Builder tlsConfig(@Nullable RingTlsConfig t) {
            this.tlsConfig = t;
            return this;
        }

        public Builder requireTlsInProduction(boolean require) {
            this.requireTlsInProduction = require;
            return this;
        }

        public Builder metrics(MetricsRecorder m) {
            this.metrics = m;
            return this;
        }

        public RingAcceptorConfig build() {
            return new RingAcceptorConfig(
                    bindAddress, port, acceptBacklog, maxConnections,
                    acceptBurst, acceptRefillPerSecond,
                    timeouts, shutdownGrace,
                    maxBodyBytes, outboundQueueCapacity, outboundQueueByteCapacity,
                    decodeMemoryBudget, tlsConfig, requireTlsInProduction, metrics);
        }
    }
}

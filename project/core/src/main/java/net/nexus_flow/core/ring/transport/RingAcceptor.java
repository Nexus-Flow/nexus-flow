package net.nexus_flow.core.ring.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.wire.FrameDecoder;
import net.nexus_flow.core.runtime.AbstractDaemonWorker;

/**
 * Accepts incoming ring connections on a {@link ServerSocket} (plain TCP) or {@link
 * SSLServerSocket} (mTLS).
 *
 * <h2>Capacity (audit finding #6)</h2>
 *
 * Connection admission is gated by a {@link Semaphore} sized at {@link
 * RingAcceptorConfig#maxConnections()}. The acquire happens BEFORE any RingConnection
 * construction; the release is unconditional (a {@link RingFrameHandler#onClosed} decorator
 * releases the permit, and the acceptor itself releases it if connection construction throws).
 * This eliminates the previous {@code AtomicInteger.incrementAndGet} race where a counter
 * could leak after a registration failure.
 *
 * <h2>Per-source accept-rate limit</h2>
 *
 * Every accept passes through {@link AcceptRateLimiter#tryAcquire(String)} keyed on the
 * remote host. Rejected attempts are closed immediately and counted via {@link
 * RingMetrics#incrementAcceptRateRejection()}. This is the front line against
 * connection-flood DoS from a single peer.
 *
 * <h2>Decode-memory budget</h2>
 *
 * Each connection's decoder reports {@code pendingBytes()} as bytes accumulate. The acceptor
 * sums these across all open connections; if the next admit would push the total over the
 * configured global budget, the new accept is closed. This complements the per-frame
 * {@code maxBodyBytes} cap with a global ceiling.
 *
 * <h2>Watchdog</h2>
 *
 * A scheduled task (every 250 ms by default) calls {@link RingConnection#checkWriteTimeout()}
 * on every live connection and {@link AcceptRateLimiter#evictStale(java.time.Duration)} on
 * the rate limiter. This is the only periodic work the acceptor schedules; everything else
 * is event-driven.
 *
 * <h2>Lifecycle (audit finding #15 / §11.3 audit log)</h2>
 *
 * Inherits the cancel → interrupt → join discipline from {@link AbstractDaemonWorker}. On
 * {@link #shutdown()}: server socket close, watchdog scheduler shutdown, per-connection
 * executor shutdown, all live connections {@link RingConnection#close(Throwable)}d with a
 * shutdown cause. Tracked connections drained from a {@link ConcurrentHashMap} so the
 * shutdown path sees every live socket.
 */
public final class RingAcceptor extends AbstractDaemonWorker {

    private static final System.Logger LOG = System.getLogger(RingAcceptor.class.getName());

    private final RingAcceptorConfig          config;
    private final RingFrameHandler            handler;
    private final RingMetrics                 metrics;
    private final Semaphore                   admissionPermits;
    private final AcceptRateLimiter           rateLimiter;
    private final Set<RingConnection>         liveConnections     = ConcurrentHashMap.newKeySet();
    private final AtomicLong                  decodeBytesEstimate = new AtomicLong();
    private volatile ServerSocket             serverSocket;
    private volatile ExecutorService          perConnectionExecutor;
    private volatile ScheduledExecutorService watchdog;
    private volatile int                      boundPort           = -1;

    public RingAcceptor(RingAcceptorConfig config, RingFrameHandler handler) {
        super("nexus-ring-acceptor-" + Objects.requireNonNull(config, "config").port());
        this.config           = config;
        this.handler          = Objects.requireNonNull(handler, "handler");
        this.metrics          = new RingMetrics(
                config.metrics(), "acceptor:" + config.bindAddress() + ":" + config.port());
        this.admissionPermits = new Semaphore(config.maxConnections(), true);
        this.rateLimiter      = new AcceptRateLimiter(
                config.acceptBurst(), config.acceptRefillPerSecond(), Clock.systemUTC());
    }

    /** Bound port (after start), or {@code -1} before bind / after shutdown. */
    public int boundPort() {
        return boundPort;
    }

    /** Number of currently live connections. */
    public int liveConnections() {
        return liveConnections.size();
    }

    /** Diagnostics: estimated decoder pre-allocated bytes summed across live connections. */
    public long decodeMemoryEstimate() {
        long sum = 0L;
        for (RingConnection c : liveConnections) {
            sum += Math.max(0, c.outboundQueueBytes());
        }
        return sum;
    }

    @Override
    protected void runLoop() {
        try {
            if (config.requireTlsInProduction() && config.tlsConfig() == null) {
                LOG.log(System.Logger.Level.ERROR,
                        () -> "RingAcceptor refusing to start: requireTlsInProduction=true but"
                                + " tlsConfig is null. Pass .requireTlsInProduction(false) for"
                                + " test/dev deployments.");
                return;
            }
            this.serverSocket          = openServerSocket();
            this.boundPort             = serverSocket.getLocalPort();
            this.perConnectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.watchdog              = Executors.newSingleThreadScheduledExecutor(
                                                                                    r -> {
                                                                                        Thread t = new Thread(r,
                                                                                                "nexus-ring-acceptor-watchdog-"
                                                                                                        + boundPort);
                                                                                        t.setDaemon(true);
                                                                                        return t;
                                                                                    });
            scheduleWatchdog();
            LOG.log(System.Logger.Level.INFO,
                    () -> "RingAcceptor bound to " + config.bindAddress() + ":" + boundPort
                            + (config.tlsConfig() == null ? " (PLAIN TCP)" : " (mTLS)"));
            if (config.tlsConfig() == null) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "RingAcceptor running WITHOUT TLS. Test/dev mode only.");
            }
            acceptLoop();
        } catch (IOException | GeneralSecurityException ex) {
            LOG.log(System.Logger.Level.ERROR,
                    () -> "RingAcceptor failed to start: " + ex.getMessage(), ex);
        } finally {
            closeAcceptorResources();
        }
    }

    @Override
    public void shutdown() {
        if (!tryBeginShutdown()) {
            return;
        }
        closeAcceptorResources();
        cancelInterruptJoin(config.shutdownGrace());
    }

    private ServerSocket openServerSocket() throws IOException, GeneralSecurityException {
        ServerSocket ss;
        if (config.tlsConfig() == null) {
            ss = ServerSocketFactory.getDefault().createServerSocket();
        } else {
            SSLContext             ctx       = config.tlsConfig().buildSslContext();
            SSLServerSocketFactory factory   = ctx.getServerSocketFactory();
            SSLServerSocket        sslServer = (SSLServerSocket) factory.createServerSocket();
            sslServer.setNeedClientAuth(true);
            sslServer.setEnabledProtocols(
                                          config.tlsConfig().protocols().toArray(new String[0]));
            if (config.tlsConfig().cipherSuites() != null) {
                sslServer.setEnabledCipherSuites(
                                                 config.tlsConfig().cipherSuites().toArray(new String[0]));
            }
            ss = sslServer;
        }
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(config.bindAddress(), config.port()), config.acceptBacklog());
        return ss;
    }

    private void acceptLoop() {
        while (isRunning()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (SocketException expectedOnShutdown) {
                return;
            } catch (IOException io) {
                metrics.incrementAcceptFailure();
                LOG.log(System.Logger.Level.WARNING,
                        () -> "RingAcceptor accept failed: " + io.getMessage(), io);
                continue;
            }
            metrics.incrementAccept(config.tlsConfig() == null ? "tcp" : "tls");
            handleAcceptedSocket(socket);
        }
    }

    @SuppressWarnings("PMD.UseTryWithResources")
    private void handleAcceptedSocket(Socket socket) {
        String sourceKey = socket.getInetAddress() == null ? "unknown" : socket.getInetAddress().getHostAddress();
        if (!rateLimiter.tryAcquire(sourceKey)) {
            metrics.incrementAcceptRateRejection();
            closeSocketQuietly(socket);
            return;
        }
        if (!admissionPermits.tryAcquire()) {
            metrics.incrementAcceptCapacityRejection();
            closeSocketQuietly(socket);
            return;
        }
        if (decodeBytesEstimate.get() > config.decodeMemoryBudget()) {
            metrics.incrementAcceptCapacityRejection();
            admissionPermits.release();
            closeSocketQuietly(socket);
            return;
        }
        // try-with-resources is not applicable here: the RingConnection is intentionally
        // handed off to the per-connection executor (reader + writer VTs) and the close
        // path runs through the wrapping handler's onClosed callback. A try-with-resources
        // would call close() at the end of THIS method, prematurely tearing down the
        // connection before the reader/writer VTs ever ran. The handoff invariant is
        // enforced by `wired` — true only after submit() succeeds; false in the catch /
        // finally path triggers explicit cleanup.
        RingConnection connection = null;
        boolean        wired      = false;
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            PeerAddress  remote  = new PeerAddress(
                    socket.getInetAddress() == null ? "unknown" : socket.getInetAddress().getHostAddress(),
                    socket.getPort());
            FrameDecoder decoder = new FrameDecoder(config.maxBodyBytes());
            connection = new RingConnection(
                    socket,
                    decoder,
                    config.outboundQueueCapacity(),
                    config.outboundQueueByteCapacity(),
                    config.timeouts(),
                    remote,
                    metrics);
            liveConnections.add(connection);
            metrics.recordActiveConnections(liveConnections.size());
            RingFrameHandler wrapped   = new TrackingHandler(handler, connection);
            RingConnection   finalConn = connection;
            perConnectionExecutor.submit(() -> finalConn.runReadLoop(wrapped));
            perConnectionExecutor.submit(finalConn::runWriteLoop);
            wired = true;
        } catch (IOException io) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "RingAcceptor failed to wire accepted socket: " + io.getMessage(), io);
        } catch (RuntimeException re) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "RingAcceptor unexpected error wiring socket: " + re.getMessage(), re);
        } finally {
            if (!wired) {
                if (connection != null) {
                    liveConnections.remove(connection);
                    connection.close(new IOException("acceptor failed to wire connection"));
                }
                admissionPermits.release();
                metrics.recordActiveConnections(liveConnections.size());
                closeSocketQuietly(socket);
            }
        }
    }

    private void scheduleWatchdog() {
        ScheduledExecutorService w = watchdog;
        if (w == null) {
            return;
        }
        w.scheduleAtFixedRate(this::watchdogTick, 250, 250, TimeUnit.MILLISECONDS);
        w.scheduleAtFixedRate(
                              () -> rateLimiter.evictStale(java.time.Duration.ofMinutes(5)),
                              60, 60, TimeUnit.SECONDS);
    }

    private void watchdogTick() {
        try {
            for (RingConnection c : liveConnections) {
                c.checkWriteTimeout();
            }
        } catch (RuntimeException re) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "RingAcceptor watchdog failed: " + re.getMessage(), re);
        }
    }

    private void closeAcceptorResources() {
        ServerSocket ss = this.serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // best-effort
            }
            boundPort = -1;
        }
        ScheduledExecutorService w = this.watchdog;
        if (w != null) {
            w.shutdownNow();
        }
        for (RingConnection c : liveConnections) {
            c.close(new IOException("acceptor shutdown"));
        }
        ExecutorService exec = this.perConnectionExecutor;
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    private static void closeSocketQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Wraps the user handler so the acceptor learns about connection close — without forcing
     * users to call {@code RingAcceptor.onClosed} themselves. Decrements live-connection set
     * and releases the admission permit.
     */
    private final class TrackingHandler implements RingFrameHandler {
        private final RingFrameHandler                          inner;
        private final RingConnection                            connection;
        private final java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        TrackingHandler(RingFrameHandler inner, RingConnection connection) {
            this.inner      = inner;
            this.connection = connection;
        }

        @Override
        public void onAccepted(RingConnection conn) {
            inner.onAccepted(conn);
        }

        @Override
        public void onFrame(RingConnection conn, net.nexus_flow.core.ring.wire.RingFrame frame) {
            inner.onFrame(conn, frame);
        }

        @Override
        public void onClosed(RingConnection conn, Throwable cause) {
            try {
                inner.onClosed(conn, cause);
            } finally {
                if (released.compareAndSet(false, true)) {
                    liveConnections.remove(connection);
                    admissionPermits.release();
                    metrics.recordActiveConnections(liveConnections.size());
                }
            }
        }
    }
}

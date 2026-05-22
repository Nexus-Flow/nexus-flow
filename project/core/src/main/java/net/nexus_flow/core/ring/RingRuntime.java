package net.nexus_flow.core.ring;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.nexus_flow.core.outbox.OutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxStorage;
import net.nexus_flow.core.outbox.OutboxWorker;
import net.nexus_flow.core.ring.dispatch.DispatchResponseEnvelope;
import net.nexus_flow.core.ring.dispatch.LocalDispatchHandler;
import net.nexus_flow.core.ring.dispatch.PendingResponseRegistry;
import net.nexus_flow.core.ring.dispatch.RingDispatcher;
import net.nexus_flow.core.ring.dispatch.RingFrameRouter;
import net.nexus_flow.core.ring.event.OutboxOwnership;
import net.nexus_flow.core.ring.event.PeerCursorTracker;
import net.nexus_flow.core.ring.event.RingEventBusBridge;
import net.nexus_flow.core.ring.event.RingOutboxBridge;
import net.nexus_flow.core.ring.membership.DefaultMembershipRegistry;
import net.nexus_flow.core.ring.membership.HeartbeatConfig;
import net.nexus_flow.core.ring.membership.HeartbeatFailureDetector;
import net.nexus_flow.core.ring.membership.MembershipRegistry;
import net.nexus_flow.core.ring.membership.StaticPeerListMembership;
import net.nexus_flow.core.ring.ops.RingHealthChecker;
import net.nexus_flow.core.ring.ops.RingOps;
import net.nexus_flow.core.ring.registry.DefaultHandlerDirectory;
import net.nexus_flow.core.ring.registry.PeerSelector;
import net.nexus_flow.core.ring.registry.RoundRobinPeerSelector;
import net.nexus_flow.core.ring.saga.LeaseClaimOutcome;
import net.nexus_flow.core.ring.saga.LeaseRegistry;
import net.nexus_flow.core.ring.saga.SagaLease;
import net.nexus_flow.core.ring.saga.SagaLeaseCoordinator;
import net.nexus_flow.core.ring.saga.SagaLeaseCoordinatorConfig;
import net.nexus_flow.core.ring.transport.CertificateSource;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingAcceptor;
import net.nexus_flow.core.ring.transport.RingAcceptorConfig;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.jspecify.annotations.Nullable;

/**
 * Coordinator that wires the ring's eight subsystems (membership registry, connection registry,
 * heartbeat detector, saga lease coordinator, pending-response registry, dispatcher, frame router,
 * acceptor) behind a single fluent builder. Resolves the audit's "ring UX is engorrosa" gap: the
 * user constructs and {@link #close() closes} ONE object — the runtime owns every transient
 * resource and exposes the read-mostly view through {@link #ops()}.
 *
 * <h2>What it owns</h2>
 *
 * <ul>
 * <li>{@link MembershipRegistry} — single source of truth for peer state.
 * <li>{@link RingConnectionRegistry} — alive TLS/TCP connections.
 * <li>{@link HeartbeatFailureDetector} — ALIVE → SUSPECT → CONFIRMED_DEAD transitions.
 * <li>{@link SagaLeaseCoordinator} — distributed saga ownership with fencing.
 * <li>{@link PendingResponseRegistry} — in-flight cross-pod dispatch tracking.
 * <li>{@link RingDispatcher} — cross-pod dispatch initiation.
 * <li>{@link RingFrameRouter} — inbound frame demux.
 * <li>{@link RingAcceptor} — TLS / TCP listener.
 * </ul>
 *
 * <h2>What it does NOT do (by design)</h2>
 *
 * <ul>
 * <li>The {@code RingRuntime} does not own a {@link FlowRuntime}. Adapters {@link
 * Builder#attachTo(FlowRuntime) attach} an existing core runtime for cross-pod fallback
 * bridges (RingOutboxBridge, RingEventBusBridge, RingCommandFallback). The core runtime
 * keeps its lifecycle separate.
 * <li>It does not own a dialer. Outbound connections are an opt-in extension wired through
 * adapter modules (the test fixtures dial manually; production dial-and-reconnect logic
 * belongs in a dedicated cluster-shaper component planned for a later phase).
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * The runtime owns a single-threaded scheduler used by {@link PendingResponseRegistry} for
 * timeout sweeps. Every other subsystem manages its own threading (acceptor's VT executor,
 * dispatcher's virtual threads, etc.).
 *
 * <h2>Lifecycle</h2>
 *
 * Constructed via {@link Builder}. {@link #start()} launches the acceptor on the configured
 * port (and binds it — the acceptor returns when the bind succeeds). {@link #close()} shuts down
 * every subsystem in dependency order: acceptor → connections → coordinators → scheduler. The
 * close path is idempotent; calling it twice is a no-op.
 */
public final class RingRuntime implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RingRuntime.class.getName());

    private final PeerId                       localPeerId;
    private final MembershipRegistry           membership;
    private final DefaultMembershipRegistry    mutableMembership;
    private final RingConnectionRegistry       connections;
    private final HeartbeatFailureDetector     heartbeat;
    private final SagaLeaseCoordinator         sagaCoord;
    private final PendingResponseRegistry      pending;
    private final RingDispatcher               dispatcher;
    private final RingFrameRouter              router;
    private final RingAcceptor                 acceptor;
    private final ScheduledExecutorService     scheduler;
    private final DefaultHandlerDirectory      directory;
    private final RingOps                      ops;
    private final @Nullable CertificateSource  certificateSource;
    private final @Nullable RingEventBusBridge eventBusBridge;
    private final @Nullable RingOutboxBridge   outboxBridge;
    private final @Nullable OutboxWorker       failoverOutboxWorker;
    private volatile boolean                   closed;

    private RingRuntime(Builder b) throws IOException {
        this.localPeerId = b.localPeerId;
        this.scheduler   = Executors.newSingleThreadScheduledExecutor(r -> {
                             Thread t = new Thread(r, "nexus-ring-" + b.localPeerId.value() + "-sched");
                             t.setDaemon(true);
                             return t;
                         });
        this.connections = new RingConnectionRegistry();
        StaticPeerListMembership seedMembership =
                new StaticPeerListMembership(b.clock, b.seedPeers);
        seedMembership.start();
        this.mutableMembership = seedMembership.mutableRegistry();
        this.membership        = seedMembership.registry();
        this.heartbeat         = new HeartbeatFailureDetector(
                HeartbeatConfig.defaults(b.localPeerId), b.clock, connections, mutableMembership);
        LeaseRegistry leaseRegistry = new LeaseRegistry(b.clock);
        this.sagaCoord = new SagaLeaseCoordinator(
                SagaLeaseCoordinatorConfig.defaults(b.localPeerId),
                b.clock,
                leaseRegistry,
                connections,
                (sagaId, owner, expiry) -> new LeaseClaimOutcome.Claimed(
                        new SagaLease(sagaId, owner, expiry)),
                membership);
        this.pending   = new PendingResponseRegistry(64, scheduler);
        this.directory = new DefaultHandlerDirectory();
        PeerSelector selector = b.peerSelector == null ? new RoundRobinPeerSelector() : b.peerSelector;
        this.dispatcher = new RingDispatcher(
                b.localPeerId, connections, directory, selector, pending);
        LocalDispatchHandler localHandler = b.localDispatchHandler == null ? RingRuntime::defaultLocalRejection : b.localDispatchHandler;
        this.router            = RingFrameRouter.forSingleTenantTrustedRing(
                                                                            heartbeat, sagaCoord, dispatcher, localHandler, null);
        this.acceptor          = new RingAcceptor(b.acceptorConfig, router);
        this.certificateSource = b.certificateSource;
        RingHealthChecker health = new RingHealthChecker(
                b.clock, membership, acceptor::liveConnections,
                pending::inFlight, 100, b.expectsClusteredDeployment);
        // Live fan-out (best-effort) — opt-in via Builder.enableLiveFanOut.
        if (b.liveFanOutEnabled) {
            if (b.flowRuntime == null) {
                throw new IllegalStateException(
                        "enableLiveFanOut requires attachTo(flowRuntime) to be called first");
            }
            this.eventBusBridge = new RingEventBusBridge(
                    b.localPeerId, b.flowRuntime.events(), connections, membership,
                    b.liveCodec, b.liveCodecId);
        } else {
            this.eventBusBridge = null;
        }
        // Durable fan-out (at-least-once) — opt-in via Builder.enableDurableFanOut.
        if (b.durableFanOutStorage != null) {
            this.outboxBridge         = new RingOutboxBridge(
                    b.localPeerId,
                    b.durableFanOutStorage,
                    connections,
                    membership,
                    b.durableCursors == null ? new PeerCursorTracker() : b.durableCursors,
                    b.clock,
                    b.durablePollInterval,
                    b.durableBatchSize,
                    b.durableOwnership);
            this.failoverOutboxWorker = b.failoverOutboxWorker;
            if (b.durableOwnership == OutboxOwnership.RING_BRIDGE_WITH_WORKER_FAILOVER && failoverOutboxWorker != null) {
                outboxBridge.attachOutboxWorker(failoverOutboxWorker);
            }
        } else {
            this.outboxBridge         = null;
            this.failoverOutboxWorker = null;
        }
        this.ops = RingOps.builder()
                .health(health)
                .membership(membership)
                .connections(connections)
                .acceptor(acceptor)
                .outboxBridge(outboxBridge)
                .pendingDispatches(pending::inFlight)
                .build();
    }

    /** Start the acceptor — binds the listener on the configured port. */
    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("RingRuntime is closed");
        }
        acceptor.start();
        if (outboxBridge != null) {
            outboxBridge.start();
        }
        // eventBusBridge has no explicit start — the listener is registered in its constructor.
    }

    /** Optional live (best-effort) fan-out bridge — populated when enableLiveFanOut was used. */
    public Optional<RingEventBusBridge> eventBusBridge() {
        return Optional.ofNullable(eventBusBridge);
    }

    /** Optional durable (at-least-once) fan-out bridge — populated when enableDurableFanOut was used. */
    public Optional<RingOutboxBridge> outboxBridge() {
        return Optional.ofNullable(outboxBridge);
    }

    public PeerId localPeerId() {
        return localPeerId;
    }

    public MembershipRegistry membership() {
        return membership;
    }

    public DefaultMembershipRegistry mutableMembership() {
        return mutableMembership;
    }

    public RingConnectionRegistry connections() {
        return connections;
    }

    public HeartbeatFailureDetector heartbeat() {
        return heartbeat;
    }

    public SagaLeaseCoordinator sagaCoordinator() {
        return sagaCoord;
    }

    public PendingResponseRegistry pendingResponses() {
        return pending;
    }

    public RingDispatcher dispatcher() {
        return dispatcher;
    }

    public RingFrameRouter router() {
        return router;
    }

    public RingAcceptor acceptor() {
        return acceptor;
    }

    public DefaultHandlerDirectory directory() {
        return directory;
    }

    /** Unified ops facade — read-mostly view + quiesce/drain. */
    public RingOps ops() {
        return ops;
    }

    /** The optional cert source — adapters wrapping it in a watcher use this handle to close it. */
    public Optional<CertificateSource> certificateSource() {
        return Optional.ofNullable(certificateSource);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Close bridges FIRST so they can release the worker (failover handshake) before the
        // acceptor stops accepting new frames.
        if (outboxBridge != null) {
            closeQuietly("outboxBridge", outboxBridge::close);
        }
        if (eventBusBridge != null) {
            closeQuietly("eventBusBridge", eventBusBridge::close);
        }
        closeQuietly("acceptor", acceptor::close);
        closeQuietly("heartbeat", heartbeat::close);
        closeQuietly("sagaCoord", sagaCoord::close);
        closeQuietly("pending", pending::close);
        if (certificateSource != null) {
            closeQuietly("certificateSource", certificateSource::close);
        }
        scheduler.shutdownNow();
    }

    private static void closeQuietly(String name, Runnable closer) {
        try {
            closer.run();
        } catch (RuntimeException re) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "RingRuntime: close of " + name + " failed: " + re.getMessage(), re);
        }
    }

    private static DispatchResponseEnvelope defaultLocalRejection(
            LocalDispatchHandler.LocalDispatchContext ctx) {
        return DispatchResponseEnvelope.failure(
                                                ctx.request().correlationId(),
                                                net.nexus_flow.core.ring.wire.ProtocolErrorCode.NOT_FOUND,
                                                "no local handler registered for " + ctx.request().payloadType());
    }

    public static Builder builder(PeerId localPeerId) {
        return new Builder(localPeerId);
    }

    /** Fluent builder. {@code peerId} and {@code seedPeers} are mandatory; the rest has defaults. */
    public static final class Builder {

        private final PeerId                   localPeerId;
        private Map<PeerId, PeerAddress>       seedPeers;
        private Clock                          clock                      = Clock.systemUTC();
        private RingAcceptorConfig             acceptorConfig             = RingAcceptorConfig.loopbackForTests();
        private @Nullable PeerSelector         peerSelector;
        private @Nullable LocalDispatchHandler localDispatchHandler;
        private @Nullable CertificateSource    certificateSource;
        private boolean                        expectsClusteredDeployment = true;
        private @Nullable FlowRuntime          flowRuntime;

        // Bridge auto-wiring (opt-in)
        private boolean                      liveFanOutEnabled;
        private @Nullable OutboxPayloadCodec liveCodec;
        private @Nullable String             liveCodecId;
        private @Nullable OutboxStorage      durableFanOutStorage;
        private OutboxOwnership              durableOwnership    =
                OutboxOwnership.RING_BRIDGE_ONLY;
        private @Nullable PeerCursorTracker  durableCursors;
        private Duration                     durablePollInterval =
                Duration.ofMillis(100);
        private int                          durableBatchSize    = 32;
        private @Nullable OutboxWorker       failoverOutboxWorker;

        private Builder(PeerId localPeerId) {
            this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId");
            this.seedPeers   = Map.of();
        }

        public Builder seedPeers(Map<PeerId, PeerAddress> peers) {
            this.seedPeers = Map.copyOf(Objects.requireNonNull(peers, "seedPeers"));
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Builder acceptorConfig(RingAcceptorConfig cfg) {
            this.acceptorConfig = Objects.requireNonNull(cfg, "acceptorConfig");
            return this;
        }

        public Builder peerSelector(PeerSelector selector) {
            this.peerSelector = Objects.requireNonNull(selector, "peerSelector");
            return this;
        }

        public Builder localDispatchHandler(LocalDispatchHandler handler) {
            this.localDispatchHandler = Objects.requireNonNull(handler, "localDispatchHandler");
            return this;
        }

        /**
         * Wire a {@link CertificateSource} for mTLS material. Adapters wrap this in a watcher
         * for hot rotation; the static / file-backed default is built via
         * {@link CertificateSource#ofStaticConfig(net.nexus_flow.core.ring.transport.RingTlsConfig)}.
         */
        public Builder certificateSource(CertificateSource source) {
            this.certificateSource = Objects.requireNonNull(source, "certificateSource");
            return this;
        }

        /** Configure how zero-alive-peers maps to health: {@code true}=DOWN, {@code false}=UP. */
        public Builder expectsClusteredDeployment(boolean flag) {
            this.expectsClusteredDeployment = flag;
            return this;
        }

        /**
         * Attach the ring to an existing {@link FlowRuntime}. Required when enabling auto-
         * wiring of {@link RingEventBusBridge} via
         * {@link #enableLiveFanOut(OutboxPayloadCodec, String)} so
         * the bridge can subscribe to {@code flowRuntime.events()}.
         */
        public Builder attachTo(FlowRuntime flowRuntime) {
            this.flowRuntime = Objects.requireNonNull(flowRuntime, "flowRuntime");
            return this;
        }

        /**
         * Enable live (best-effort) fan-out via {@link
         * RingEventBusBridge}. Requires {@link
         * #attachTo(FlowRuntime)} to have been called — the bridge subscribes to the runtime's
         * event bus. The bridge is registered automatically by {@link #build()}; it has no
         * explicit start (the listener registration in the bridge's constructor is enough).
         */
        public Builder enableLiveFanOut(
                OutboxPayloadCodec codec, String codecId) {
            this.liveFanOutEnabled = true;
            this.liveCodec         = Objects.requireNonNull(codec, "codec");
            this.liveCodecId       = Objects.requireNonNull(codecId, "codecId");
            return this;
        }

        /**
         * Enable durable (at-least-once) fan-out via {@link
         * RingOutboxBridge}. The bridge owns the periodic drain;
         * {@link #start()} on the runtime starts the bridge as well. For
         * {@link OutboxOwnership#RING_BRIDGE_WITH_WORKER_FAILOVER},
         * also call {@link #failoverWorker(OutboxWorker)} so the
         * handshake pauses the worker at start and resumes it at close.
         */
        public Builder enableDurableFanOut(
                OutboxStorage storage,
                OutboxOwnership ownership) {
            this.durableFanOutStorage = Objects.requireNonNull(storage, "storage");
            this.durableOwnership     = Objects.requireNonNull(ownership, "ownership");
            return this;
        }

        public Builder durableCursors(PeerCursorTracker cursors) {
            this.durableCursors = Objects.requireNonNull(cursors, "cursors");
            return this;
        }

        public Builder durablePollInterval(Duration pollInterval) {
            Objects.requireNonNull(pollInterval, "pollInterval");
            if (pollInterval.isNegative() || pollInterval.isZero()) {
                throw new IllegalArgumentException(
                        "durablePollInterval must be positive: " + pollInterval);
            }
            this.durablePollInterval = pollInterval;
            return this;
        }

        public Builder durableBatchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("durableBatchSize must be >= 1: " + batchSize);
            }
            this.durableBatchSize = batchSize;
            return this;
        }

        /**
         * Register the {@link OutboxWorker} that the durable bridge
         * pauses at start and resumes at close — only honoured when ownership is {@link
         * OutboxOwnership#RING_BRIDGE_WITH_WORKER_FAILOVER}.
         */
        public Builder failoverWorker(OutboxWorker worker) {
            this.failoverOutboxWorker = Objects.requireNonNull(worker, "worker");
            return this;
        }

        /** Build a fresh ring runtime. The instance is NOT started — call {@link #start()}. */
        public RingRuntime build() throws IOException, GeneralSecurityException {
            if (certificateSource != null) {
                // Validate that the cert material is loadable before the acceptor binds; this is
                // a fail-fast at builder time instead of at the first inbound TLS handshake.
                certificateSource.load();
            }
            // flowRuntime is reserved for future bridge auto-wiring; the reference is kept on
            // the runtime instance only when set, but is currently not consumed.
            if (flowRuntime != null) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "RingRuntime.attachTo(FlowRuntime) registered — bridges remain"
                                + " separately wired (see RingOutboxBridge / RingEventBusBridge /"
                                + " RingCommandFallback for opt-in auto-wiring)");
            }
            return new RingRuntime(this);
        }
    }
}

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
import net.nexus_flow.core.inbox.InboxStorage;
import net.nexus_flow.core.outbox.OutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxStorage;
import net.nexus_flow.core.outbox.OutboxWorker;
import net.nexus_flow.core.ring.dispatch.CommandPayloadCodec;
import net.nexus_flow.core.ring.dispatch.DispatchResponseEnvelope;
import net.nexus_flow.core.ring.dispatch.LocalDispatchHandler;
import net.nexus_flow.core.ring.dispatch.PendingResponseRegistry;
import net.nexus_flow.core.ring.dispatch.RingDispatcher;
import net.nexus_flow.core.ring.dispatch.RingFrameRouter;
import net.nexus_flow.core.ring.dispatch.RouterFaultLimits;
import net.nexus_flow.core.ring.dispatch.RuntimeBackedLocalDispatchHandler;
import net.nexus_flow.core.ring.event.EventBusRingInboundHandler;
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
import net.nexus_flow.core.ring.saga.SagaOwnershipClaimer;
import net.nexus_flow.core.ring.saga.StorageBackedSagaOwnershipClaimer;
import net.nexus_flow.core.ring.transport.CertificateSource;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingAcceptor;
import net.nexus_flow.core.ring.transport.RingAcceptorConfig;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.saga.SagaStorage;
import org.jspecify.annotations.Nullable;

/**
 * Coordinator that wires the ring's eight subsystems (membership registry, connection registry,
 * heartbeat detector, saga lease coordinator, pending-response registry, dispatcher, frame router,
 * acceptor) behind a single fluent builder. Collapses what would otherwise be eight separate
 * object lifetimes into one: the caller constructs and {@link #close() closes} a single
 * runtime; the runtime owns every transient resource and exposes the read-mostly view through
 * {@link #ops()}.
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

    /**
     * Default in-flight cap for the {@link PendingResponseRegistry}. Sized to comfortably
     * cover single-host load (one inbound request per ~16 µs at 4k RPS for 1 ms median
     * dispatch). Multi-tenant deployments routing thousands of concurrent dispatches raise
     * this knob via {@link Builder#pendingResponseRegistryCapacity(int)}.
     */
    public static final int DEFAULT_PENDING_RESPONSE_REGISTRY_CAPACITY = 64;

    /**
     * Default virtual-node count for the default {@link
     * net.nexus_flow.core.ring.registry.HashRingPeerSelector}. Higher values smooth load
     * distribution at the cost of more per-select work. Mirrors {@link
     * net.nexus_flow.core.ring.registry.HashRingPeerSelector#DEFAULT_VIRTUAL_NODES}.
     */
    public static final int DEFAULT_HASH_RING_VIRTUAL_NODES =
            net.nexus_flow.core.ring.registry.HashRingPeerSelector.DEFAULT_VIRTUAL_NODES;

    /**
     * Default ring-cache cap for the default {@link
     * net.nexus_flow.core.ring.registry.HashRingPeerSelector}. Stable deployments routing
     * to fewer than this many distinct candidate sets hit the cache on every dispatch;
     * volatile sets beyond the cap recompute on demand. Mirrors {@link
     * net.nexus_flow.core.ring.registry.HashRingPeerSelector#DEFAULT_MAX_CACHED_RINGS}.
     */
    public static final int DEFAULT_HASH_RING_MAX_CACHED_RINGS =
            net.nexus_flow.core.ring.registry.HashRingPeerSelector.DEFAULT_MAX_CACHED_RINGS;

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
        LeaseRegistry        leaseRegistry    = new LeaseRegistry(b.clock);
        SagaOwnershipClaimer effectiveClaimer = b.sagaStorage != null ? new StorageBackedSagaOwnershipClaimer(b.sagaStorage, b.clock) : (
                sagaId, owner, expiry) -> new LeaseClaimOutcome.Claimed(
                        new SagaLease(sagaId, owner, expiry));
        this.sagaCoord = new SagaLeaseCoordinator(
                SagaLeaseCoordinatorConfig.defaults(b.localPeerId),
                b.clock,
                leaseRegistry,
                connections,
                effectiveClaimer,
                membership);
        this.pending   = new PendingResponseRegistry(b.pendingResponseRegistryCapacity, scheduler);
        this.directory = new DefaultHandlerDirectory();
        // When no explicit selector is wired the runtime keeps the historical default
        // (RoundRobinPeerSelector). The hash-ring knobs only apply when the operator opts
        // into the hash-ring selector via .peerSelector(new HashRingPeerSelector(...)) OR
        // via .hashRingSelector() — the builder method below constructs a HashRing selector
        // sized from the configured knobs.
        PeerSelector selector = b.peerSelector == null ? new RoundRobinPeerSelector() : b.peerSelector;
        this.dispatcher = new RingDispatcher(
                b.localPeerId, connections, directory, selector, pending);
        LocalDispatchHandler                    localHandler        = resolveLocalDispatchHandler(b);
        RingFrameRouter.RingEventInboundHandler eventInboundHandler = resolveEventInboundHandler(b);
        this.router            = new RingFrameRouter(
                heartbeat,
                sagaCoord,
                dispatcher,
                localHandler,
                net.nexus_flow.core.ring.dispatch.DispatchAuthorizer.ALLOW_ALL,
                net.nexus_flow.core.ring.observability.RingMetrics.noOp(),
                b.clock,
                eventInboundHandler,
                b.faultLimits);
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

    /**
     * Resolution priority for the inbound COMMAND/QUERY handler:
     *
     * <ol>
     * <li>Explicit {@link Builder#localDispatchHandler(LocalDispatchHandler)} — wins
     * unconditionally; operators that need a fully custom local-dispatch path pin this.
     * <li>{@link Builder#enableLocalDispatch(CommandPayloadCodec)} + {@link
     * Builder#attachTo(FlowRuntime)} — the framework wires a {@link
     * RuntimeBackedLocalDispatchHandler} that bridges to {@code flowRuntime.commands()} /
     * {@code .queries()}. This is the production default.
     * <li>Neither set — fall back to {@link #defaultLocalRejection}, which answers every
     * inbound command/query with {@code NOT_FOUND}. Suitable only for ring deployments
     * that intentionally never accept cross-pod commands (e.g. one-way fan-out
     * topologies).
     * </ol>
     */
    private static LocalDispatchHandler resolveLocalDispatchHandler(Builder b) {
        if (b.localDispatchHandler != null) {
            return b.localDispatchHandler;
        }
        if (b.localDispatchCodec != null) {
            if (b.flowRuntime == null) {
                throw new IllegalStateException(
                        "enableLocalDispatch requires attachTo(flowRuntime) so the handler can"
                                + " route commands/queries to the runtime's buses");
            }
            RuntimeBackedLocalDispatchHandler.Builder builder = RuntimeBackedLocalDispatchHandler.builder()
                    .runtime(b.flowRuntime)
                    .codec(b.localDispatchCodec);
            if (b.localDispatchClassResolver != null) {
                builder.classResolver(b.localDispatchClassResolver);
            }
            return builder.build();
        }
        return RingRuntime::defaultLocalRejection;
    }

    /**
     * Resolution priority for the inbound EVENT handler:
     *
     * <ol>
     * <li>Explicit {@link Builder#eventInboundHandler(RingFrameRouter.RingEventInboundHandler)}
     * — wins unconditionally.
     * <li>{@link Builder#enableLiveFanOut(OutboxPayloadCodec, String)} + {@link
     * Builder#attachTo(FlowRuntime)} — the framework wires a {@link
     * EventBusRingInboundHandler} so the bus and ring are bidirectional. The handler
     * uses {@link Builder#inboxStorage(InboxStorage)} for dedup when wired.
     * <li>Neither set — return {@code null}; inbound EVENT frames are dropped silently. This
     * is the previous (pre-fix) behaviour and is only safe for one-way ring topologies.
     * </ol>
     */
    private static RingFrameRouter.@Nullable RingEventInboundHandler resolveEventInboundHandler(Builder b) {
        if (b.eventInboundHandler != null) {
            return b.eventInboundHandler;
        }
        if (!b.liveFanOutEnabled) {
            return null;
        }
        if (b.flowRuntime == null) {
            // enableLiveFanOut already validated this at the call site, but defensive check
            // here keeps the resolver self-contained for future callers.
            return null;
        }
        EventBusRingInboundHandler.Builder builder = EventBusRingInboundHandler.builder()
                .eventBus(b.flowRuntime.events())
                .codec(b.liveCodec)
                .expectedCodecId(b.liveCodecId)
                .clock(b.clock);
        if (b.inboxStorage != null) {
            builder.inbox(b.inboxStorage);
        }
        if (b.inboxConsumerId != null) {
            builder.consumerId(b.inboxConsumerId);
        }
        return builder.build();
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

        // Inbound dispatch wiring (opt-in)
        private @Nullable CommandPayloadCodec                             localDispatchCodec;
        private RuntimeBackedLocalDispatchHandler.@Nullable ClassResolver localDispatchClassResolver;
        private RingFrameRouter.@Nullable RingEventInboundHandler         eventInboundHandler;
        private @Nullable InboxStorage                                    inboxStorage;
        private @Nullable String                                          inboxConsumerId;
        private @Nullable SagaStorage                                     sagaStorage;
        private RouterFaultLimits                                         faultLimits = RouterFaultLimits.DEFAULTS;

        // Capacity / sizing knobs
        private int pendingResponseRegistryCapacity = DEFAULT_PENDING_RESPONSE_REGISTRY_CAPACITY;
        private int hashRingVirtualNodes            = DEFAULT_HASH_RING_VIRTUAL_NODES;
        private int hashRingMaxCachedRings          = DEFAULT_HASH_RING_MAX_CACHED_RINGS;

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

        /**
         * Enable inbound COMMAND/QUERY dispatch backed by the attached {@link FlowRuntime}.
         * Without this call (or an explicit {@link #localDispatchHandler(LocalDispatchHandler)})
         * the router rejects every cross-pod command with {@code NOT_FOUND}, which makes the
         * outbound side ({@link net.nexus_flow.core.ring.dispatch.RingCommandFallback}) useless
         * because no peer ever serves the request.
         *
         * <p>Requires {@link #attachTo(FlowRuntime)} so the handler can route to {@code
         * flowRuntime.commands()} / {@code .queries()}. The codec MUST match the codec used by
         * the senders' {@link
         * net.nexus_flow.core.ring.dispatch.RingCommandFallback} — sender and receiver are bound
         * to the same wire format via {@link CommandPayloadCodec#codecId()}.
         */
        public Builder enableLocalDispatch(CommandPayloadCodec codec) {
            this.localDispatchCodec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Install a custom {@link RuntimeBackedLocalDispatchHandler.ClassResolver} for the
         * inbound dispatch path — production deployments use this to install a payload-type
         * allowlist instead of the default {@code Class.forName} reflection.
         */
        public Builder localDispatchClassResolver(
                RuntimeBackedLocalDispatchHandler.ClassResolver resolver) {
            this.localDispatchClassResolver = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        /**
         * Pin a fully custom inbound EVENT handler. Wins over {@link
         * #enableLiveFanOut(OutboxPayloadCodec, String)}-implied wiring. Use when the
         * application needs special handling (e.g. routing inbound events through a saga before
         * the bus, encrypted payload unwrap).
         */
        public Builder eventInboundHandler(RingFrameRouter.RingEventInboundHandler handler) {
            this.eventInboundHandler = Objects.requireNonNull(handler, "eventInboundHandler");
            return this;
        }

        /**
         * Wire an {@link InboxStorage} so the inbound EVENT handler can deduplicate
         * events arriving via both the live fan-out and the durable outbox replay paths. When
         * unset the receiver dispatches every inbound event without dedup — acceptable for
         * cache-invalidation / presence updates; required for exactly-once-effective business
         * delivery across pods.
         */
        public Builder inboxStorage(InboxStorage inbox) {
            this.inboxStorage = Objects.requireNonNull(inbox, "inbox");
            return this;
        }

        /**
         * Override the consumer id stamped on inbox rows. Defaults to {@link
         * EventBusRingInboundHandler#DEFAULT_CONSUMER_ID}. Useful when multiple ring runtimes
         * share an inbox table and need separate dedup namespaces.
         */
        public Builder inboxConsumerId(String consumerId) {
            this.inboxConsumerId = Objects.requireNonNull(consumerId, "consumerId");
            return this;
        }

        /**
         * Wire a {@link SagaStorage} so the {@link SagaLeaseCoordinator} performs ownership
         * claims through an atomic compare-and-set against durable storage instead of the
         * in-memory test stub that grants every claim. Production deployments MUST set this —
         * without it, two peers can each believe they own the same saga simultaneously.
         */
        public Builder sagaStorage(SagaStorage storage) {
            this.sagaStorage = Objects.requireNonNull(storage, "storage");
            return this;
        }

        /**
         * Override the per-peer fault-budget thresholds used by {@link RingFrameRouter} to
         * decide when a misbehaving connection must be evicted. Defaults to
         * {@link RouterFaultLimits#DEFAULTS}.
         */
        public Builder faultLimits(RouterFaultLimits limits) {
            this.faultLimits = Objects.requireNonNull(limits, "faultLimits");
            return this;
        }

        /**
         * Override the maximum number of in-flight cross-pod dispatches the {@link
         * PendingResponseRegistry} will track simultaneously. Reaching the cap surfaces
         * backpressure-shaped saturation on the caller of {@link RingDispatcher#dispatch}.
         * Default: {@link #DEFAULT_PENDING_RESPONSE_REGISTRY_CAPACITY}. Must be {@code >= 1}.
         */
        public Builder pendingResponseRegistryCapacity(int capacity) {
            if (capacity < 1) {
                throw new IllegalArgumentException(
                        "pendingResponseRegistryCapacity must be >= 1: " + capacity);
            }
            this.pendingResponseRegistryCapacity = capacity;
            return this;
        }

        /**
         * Override the virtual-node count used by {@link #hashRingSelector()} when the
         * application picks the hash-ring selector. Must be {@code >= 1}. Default:
         * {@link #DEFAULT_HASH_RING_VIRTUAL_NODES}.
         */
        public Builder hashRingVirtualNodes(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("hashRingVirtualNodes must be >= 1: " + n);
            }
            this.hashRingVirtualNodes = n;
            return this;
        }

        /**
         * Override the ring-cache cap used by {@link #hashRingSelector()} when the
         * application picks the hash-ring selector. Must be {@code >= 1}. Default:
         * {@link #DEFAULT_HASH_RING_MAX_CACHED_RINGS}.
         */
        public Builder hashRingMaxCachedRings(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("hashRingMaxCachedRings must be >= 1: " + n);
            }
            this.hashRingMaxCachedRings = n;
            return this;
        }

        /**
         * Install a {@link net.nexus_flow.core.ring.registry.HashRingPeerSelector} sized
         * from {@link #hashRingVirtualNodes(int)} and {@link #hashRingMaxCachedRings(int)}.
         * Convenience for callers that want aggregate affinity without explicitly
         * constructing the selector.
         */
        public Builder hashRingSelector() {
            this.peerSelector = new net.nexus_flow.core.ring.registry.HashRingPeerSelector(
                    hashRingVirtualNodes, hashRingMaxCachedRings);
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

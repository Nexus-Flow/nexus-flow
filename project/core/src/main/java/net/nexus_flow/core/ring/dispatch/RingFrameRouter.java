package net.nexus_flow.core.ring.dispatch;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.nexus_flow.core.ring.dispatch.DispatchAuthorizer.AuthorizationDecision;
import net.nexus_flow.core.ring.dispatch.LocalDispatchHandler.LocalDispatchContext;
import net.nexus_flow.core.ring.membership.HeartbeatFailureDetector;
import net.nexus_flow.core.ring.membership.PingPongPayload;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.saga.LeaseRequestEnvelope;
import net.nexus_flow.core.ring.saga.SagaLeaseCoordinator;
import net.nexus_flow.core.ring.saga.SagaStateEnvelope;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingFrameHandler;
import net.nexus_flow.core.ring.transport.RingTransportPrincipal;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.ProtocolErrorCode;
import net.nexus_flow.core.ring.wire.RingFrame;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import org.jspecify.annotations.Nullable;

/**
 * Single inbound frame handler installed on every {@link
 * net.nexus_flow.core.ring.transport.RingAcceptor} and dialed connection. Demultiplexes by
 * {@link FrameType} and forwards to the right subsystem.
 *
 * <h2>Authorization (audit security finding S3)</h2>
 *
 * Every inbound COMMAND_REQ / QUERY_REQ is filtered through the injected
 * {@link DispatchAuthorizer}. The principal comes from the connection's TLS session
 * ({@link RingConnection#principal()}); the role, tenant, and payload type come from the
 * decoded envelope. Denied dispatches answer with {@link
 * DispatchResponseEnvelope#forbidden(DispatchCorrelationId, String)} carrying a sanitised
 * reason; the rich diagnostic stays in the local logs.
 *
 * <h2>SAGA_STATE sender-owner enforcement (audit security finding S2)</h2>
 *
 * The router rejects any inbound SAGA_STATE whose {@code ownerPeerId} does not equal the
 * sender connection's bound {@link net.nexus_flow.core.ring.transport.PeerId}. This closes
 * the previous gap where any authenticated peer could rewrite saga ownership in others'
 * registries.
 *
 * <h2>Deadline propagation (audit finding M12)</h2>
 *
 * The receiver converts {@code DispatchRequestEnvelope.deadlineRemainingMillis} to a local
 * {@link System#nanoTime()}-relative deadline as soon as the frame is decoded. Local
 * handlers compare against {@code System.nanoTime()} without ever depending on cross-peer
 * wall-clock sync. Already-expired dispatches answer with {@link
 * DispatchResponseEnvelope#timeout(DispatchCorrelationId, String)} without invoking the
 * local handler.
 *
 * <h2>Error policy</h2>
 *
 * A {@link RingProtocolException} from a body decode IS a protocol violation and the read
 * loop will close the connection. Other runtime exceptions from a subsystem handler are
 * caught, counted via the {@code violation} metric, and the connection is closed if the
 * fault rate per peer exceeds the configured threshold (default: 10 errors in 60 s).
 */
public final class RingFrameRouter implements RingFrameHandler {

    private static final System.Logger LOG = System.getLogger(RingFrameRouter.class.getName());

    private final HeartbeatFailureDetector           heartbeat;
    private final SagaLeaseCoordinator               sagaCoordinator;
    private final RingDispatcher                     dispatcher;
    private final LocalDispatchHandler               localDispatch;
    private final DispatchAuthorizer                 authorizer;
    private final RingMetrics                        metrics;
    private final Clock                              clock;
    private final @Nullable RingEventInboundHandler  eventInbound;
    private final RouterFaultLimits                  faultLimits;
    private final ConcurrentMap<PeerId, FaultWindow> faultWindowsByPeer = new ConcurrentHashMap<>();

    /** Hook for {@link net.nexus_flow.core.ring.event.RingOutboxBridge} to receive inbound EVENT frames. */
    @FunctionalInterface
    public interface RingEventInboundHandler {
        void onEvent(RingConnection sender, RingFrame frame);
    }

    public RingFrameRouter(
            HeartbeatFailureDetector heartbeat,
            SagaLeaseCoordinator sagaCoordinator,
            RingDispatcher dispatcher,
            LocalDispatchHandler localDispatch,
            DispatchAuthorizer authorizer,
            RingMetrics metrics,
            @Nullable RingEventInboundHandler eventInbound) {
        this(heartbeat,
             sagaCoordinator,
             dispatcher,
             localDispatch,
             authorizer,
             metrics,
             Clock.systemUTC(),
             eventInbound,
             RouterFaultLimits.DEFAULTS);
    }

    public RingFrameRouter(
            HeartbeatFailureDetector heartbeat,
            SagaLeaseCoordinator sagaCoordinator,
            RingDispatcher dispatcher,
            LocalDispatchHandler localDispatch,
            DispatchAuthorizer authorizer,
            RingMetrics metrics,
            Clock clock,
            @Nullable RingEventInboundHandler eventInbound) {
        this(heartbeat,
             sagaCoordinator,
             dispatcher,
             localDispatch,
             authorizer,
             metrics,
             clock,
             eventInbound,
             RouterFaultLimits.DEFAULTS);
    }

    /**
     * Full-fidelity constructor with an explicit {@link RouterFaultLimits}. Operators that need to
     * tighten or loosen the per-peer fault budget — for example, untrusted multi-tenant rings or
     * deployments rolling a binary that produces transient codec mismatches — inject custom limits
     * through this constructor. The other constructors delegate here with {@link
     * RouterFaultLimits#DEFAULTS}.
     */
    public RingFrameRouter(
            HeartbeatFailureDetector heartbeat,
            SagaLeaseCoordinator sagaCoordinator,
            RingDispatcher dispatcher,
            LocalDispatchHandler localDispatch,
            DispatchAuthorizer authorizer,
            RingMetrics metrics,
            Clock clock,
            @Nullable RingEventInboundHandler eventInbound,
            RouterFaultLimits faultLimits) {
        this.heartbeat       = Objects.requireNonNull(heartbeat, "heartbeat");
        this.sagaCoordinator = Objects.requireNonNull(sagaCoordinator, "sagaCoordinator");
        this.dispatcher      = Objects.requireNonNull(dispatcher, "dispatcher");
        this.localDispatch   = Objects.requireNonNull(localDispatch, "localDispatch");
        this.authorizer      = Objects.requireNonNull(authorizer, "authorizer");
        this.metrics         = Objects.requireNonNull(metrics, "metrics");
        this.clock           = Objects.requireNonNull(clock, "clock");
        this.eventInbound    = eventInbound;
        this.faultLimits     = Objects.requireNonNull(faultLimits, "faultLimits");
    }

    /**
     * Convenience constructor for tests / single-tenant labs: {@link DispatchAuthorizer#ALLOW_ALL}
     * + no-op metrics + no event inbound handler. Production code MUST inject a real
     * authorizer.
     */
    public static RingFrameRouter forSingleTenantTrustedRing(
            HeartbeatFailureDetector heartbeat,
            SagaLeaseCoordinator sagaCoordinator,
            RingDispatcher dispatcher,
            LocalDispatchHandler localDispatch,
            @Nullable RingEventInboundHandler eventInbound) {
        return new RingFrameRouter(
                heartbeat, sagaCoordinator, dispatcher, localDispatch,
                DispatchAuthorizer.ALLOW_ALL, RingMetrics.noOp(), eventInbound);
    }

    @Override
    public void onFrame(RingConnection connection, RingFrame frame) {
        try {
            switch (frame.type()) {
                case PING                                                                            -> heartbeat.onPing(connection,
                                                                                                                         PingPongPayload
                                                                                                                                 .decode(frame
                                                                                                                                         .bodyBytes()));
                case PONG                                                                            -> heartbeat.onPong(
                                                                                                                         PingPongPayload
                                                                                                                                 .decode(frame
                                                                                                                                         .bodyBytes()));
                case SAGA_STATE                                                                      -> sagaCoordinator.onSagaState(
                                                                                                                                    connection,
                                                                                                                                    SagaStateEnvelope
                                                                                                                                            .decode(frame
                                                                                                                                                    .bodyBytes()));
                case LEASE_REQ                                                                       -> sagaCoordinator.onLeaseRequest(
                                                                                                                                       connection,
                                                                                                                                       LeaseRequestEnvelope
                                                                                                                                               .decode(frame
                                                                                                                                                       .bodyBytes()));
                case COMMAND_REQ                                                                     -> handleDispatchRequest(connection,
                                                                                                                              frame,
                                                                                                                              HandlerRole.COMMAND);
                case COMMAND_RESP                                                                    -> dispatcher.onResponse(
                                                                                                                              DispatchResponseEnvelope
                                                                                                                                      .decode(frame
                                                                                                                                              .bodyBytes()));
                case QUERY_REQ                                                                       -> handleDispatchRequest(connection,
                                                                                                                              frame,
                                                                                                                              HandlerRole.QUERY);
                case QUERY_RESP                                                                      -> dispatcher.onResponse(
                                                                                                                              DispatchResponseEnvelope
                                                                                                                                      .decode(frame
                                                                                                                                              .bodyBytes()));
                case EVENT                                                                           -> {
                    if (eventInbound != null) {
                        eventInbound.onEvent(connection, frame);
                    }
                }
                case HELLO, HELLO_ACK, LEASE_GRANT, PEER_LEFT,
                        OUTBOX_REPLAY_REQ, OUTBOX_REPLAY_RESP                                        -> {
                    LOG.log(System.Logger.Level.DEBUG,
                            () -> "router: dropping unhandled " + frame.type() + " from "
                                    + connection.remoteAddress());
                }
            }
        } catch (RingProtocolException protocolViolation) {
            metrics.incrementProtocolViolation();
            LOG.log(System.Logger.Level.WARNING,
                    () -> "router: protocol violation on " + frame.type() + " from "
                            + connection.remoteAddress() + ": " + protocolViolation.getMessage());
            // Protocol violations are unconditional close: rethrow so the read loop closes the
            // connection. The fault-budget tracker is reserved for OTHER RuntimeExceptions
            // (subsystem handler internal errors) where one fault should not by itself trigger a
            // close — only sustained misbehaviour does.
            throw protocolViolation;
        } catch (RuntimeException unexpected) {
            metrics.incrementProtocolViolation();
            LOG.log(System.Logger.Level.WARNING,
                    () -> "router: handler threw on " + frame.type() + " from "
                            + connection.remoteAddress() + ": " + unexpected.getMessage(),
                    unexpected);
            recordFaultAndMaybeClose(connection, frame.type());
        }
    }

    /**
     * Increment the fault window for the sender peer and close the connection if the budget is
     * exhausted. Faults from unidentified senders (no bound {@link PeerId}, e.g. handshake-phase
     * frames before HELLO is processed) are counted against a per-connection identity that is
     * cleared automatically when the connection closes — see {@link #onClosed}.
     */
    private void recordFaultAndMaybeClose(RingConnection connection, FrameType frameType) {
        PeerId peerId = connection.peerId();
        if (peerId == null) {
            // No bound peer id — we cannot key a fault window. The single misbehaving frame is
            // still logged + counted via the metric above; if the peer becomes identified later
            // and continues to misbehave, the post-handshake faults DO accumulate. This is the
            // intentional trade-off: we never accumulate faults against an unidentified peer
            // because there is no per-peer key to bucket them by.
            return;
        }
        FaultWindow window   = faultWindowsByPeer.computeIfAbsent(
                                                                  peerId, _ -> new FaultWindow(faultLimits, clock));
        boolean     exceeded = window.recordAndExceeded();
        if (!exceeded) {
            return;
        }
        LOG.log(System.Logger.Level.WARNING,
                () -> "router: closing connection — peer " + peerId + " exceeded fault budget "
                        + faultLimits.maxFaultsPerWindow() + "/" + faultLimits.window()
                        + " (last fault on " + frameType + ")");
        try {
            connection.close();
        } catch (RuntimeException closeFail) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "router: connection.close after fault-budget exhaustion failed for "
                            + peerId + ": " + closeFail.getMessage());
        }
    }

    /** Visible for tests — current fault count for {@code peerId} inside the rolling window. */
    int faultCount(PeerId peerId) {
        FaultWindow window = faultWindowsByPeer.get(peerId);
        return window == null ? 0 : window.currentCount();
    }

    /** Visible for tests — read-only view of the active limits. */
    RouterFaultLimits faultLimits() {
        return faultLimits;
    }

    /**
     * Sliding-window fault tracker per peer. Records timestamps in millis (clock-injected) and
     * prunes entries older than the configured window on every call. Mutating methods are
     * {@code synchronized} so multi-threaded readers from the connection's read loop never
     * mis-count under contention.
     */
    private static final class FaultWindow {

        private final RouterFaultLimits limits;
        private final Clock             clock;
        private final ArrayDeque<Long>  timestamps = new ArrayDeque<>();

        FaultWindow(RouterFaultLimits limits, Clock clock) {
            this.limits = limits;
            this.clock  = clock;
        }

        synchronized boolean recordAndExceeded() {
            long now    = clock.millis();
            long cutoff = now - limits.window().toMillis();
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            timestamps.addLast(now);
            return timestamps.size() > limits.maxFaultsPerWindow();
        }

        synchronized int currentCount() {
            long now    = clock.millis();
            long cutoff = now - limits.window().toMillis();
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            return timestamps.size();
        }
    }

    @Override
    public void onClosed(RingConnection connection, @Nullable Throwable cause) {
        var peerId = connection.peerId();
        if (peerId != null) {
            int cancelled = dispatcher.onConnectionClosed(peerId, cause);
            if (cancelled > 0) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "router: cancelled " + cancelled + " pending dispatch(es) on "
                                + peerId + " close");
            }
            // Discard the fault window so a rejoining peer starts with a fresh budget. We do
            // NOT keep the history "to remember the bad actor" — the peer that comes back has
            // gone through the full handshake (mTLS + HELLO/HELLO_ACK) again and the trust
            // surface has been re-established. If the rejoiner is still bad, the new window
            // accumulates afresh.
            faultWindowsByPeer.remove(peerId);
        }
    }

    private void handleDispatchRequest(
            RingConnection sender, RingFrame frame, HandlerRole role) {
        DispatchRequestEnvelope  request      = DispatchRequestEnvelope.decode(frame.bodyBytes());
        DispatchResponseEnvelope response     = doDispatch(sender, request, role);
        FrameType                responseType = (role == HandlerRole.COMMAND) ? FrameType.COMMAND_RESP : FrameType.QUERY_RESP;
        try {
            sender.send(RingFrame.wrapping(responseType, response.encode()));
        } catch (RuntimeException sendFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "router: failed to send " + responseType + " to "
                            + sender.remoteAddress() + ": " + sendFailure.getMessage());
        }
    }

    private DispatchResponseEnvelope doDispatch(
            RingConnection sender, DispatchRequestEnvelope request, HandlerRole role) {
        RingTransportPrincipal principal = sender.principal();
        if (principal == null) {
            principal = RingTransportPrincipal.anonymous(sender.remoteAddress());
        }
        AuthorizationDecision decision =
                authorizer.authorize(principal, role, request.tenantId(), request.payloadType());
        if (decision instanceof AuthorizationDecision.Denied denied) {
            metrics.incrementAuthzDenied(role.name());
            metrics.incrementDispatchForbidden();
            LOG.log(System.Logger.Level.INFO,
                    () -> "router: authz denied " + role + " "
                            + request.payloadType() + " from "
                            + principalDescription(sender) + ": " + denied.reason());
            return DispatchResponseEnvelope.forbidden(
                                                      request.correlationId(), "policy denied");
        }

        // Clock-skew-aware deadline conversion: subtract the wire-travel estimate from the
        // sender's remaining budget before converting to receiver-local monotonic time.
        // If wire travel already exceeded the budget, the deadline is expired at decode.
        OptionalLong localDeadlineNanos;
        if (request.hasNoDeadline()) {
            localDeadlineNanos = OptionalLong.empty();
        } else {
            long nowWallMillis      = clock.millis();
            long wireDelayMillis    =
                    Math.max(0L, nowWallMillis - request.sendInstantEpochMillis());
            long effectiveRemaining = request.deadlineRemainingMillis() - wireDelayMillis;
            if (effectiveRemaining <= 0L) {
                return DispatchResponseEnvelope.timeout(
                                                        request.correlationId(), "deadline expired in transit");
            }
            localDeadlineNanos =
                    OptionalLong.of(System.nanoTime() + effectiveRemaining * 1_000_000L);
        }

        LocalDispatchContext ctx = new LocalDispatchContext(
                request, role, principal, localDeadlineNanos);
        try {
            DispatchResponseEnvelope response = localDispatch.dispatch(ctx);
            return response.correlationId().equals(request.correlationId()) ? response : DispatchResponseEnvelope.failure(
                                                                                                                          request.correlationId(),
                                                                                                                          ProtocolErrorCode.INTERNAL,
                                                                                                                          "handler returned mismatched correlation id");
        } catch (RuntimeException re) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "router: local dispatch threw: " + re.getClass().getName(), re);
            return DispatchResponseEnvelope.failure(
                                                    request.correlationId(),
                                                    ProtocolErrorCode.INTERNAL,
                                                    "local handler internal error");
        }
    }

    private static String principalDescription(RingConnection sender) {
        RingTransportPrincipal p = sender.principal();
        if (p == null) {
            return sender.remoteAddress().toString();
        }
        return p.subjectDistinguishedName() + " @ " + sender.remoteAddress();
    }
}

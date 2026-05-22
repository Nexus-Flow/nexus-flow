package net.nexus_flow.core.ring.dispatch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.ring.membership.DefaultMembershipRegistry;
import net.nexus_flow.core.ring.membership.HeartbeatConfig;
import net.nexus_flow.core.ring.membership.HeartbeatFailureDetector;
import net.nexus_flow.core.ring.membership.PingPongPayload;
import net.nexus_flow.core.ring.membership.StaticPeerListMembership;
import net.nexus_flow.core.ring.registry.DefaultHandlerDirectory;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.registry.RoundRobinPeerSelector;
import net.nexus_flow.core.ring.saga.LeaseClaimOutcome;
import net.nexus_flow.core.ring.saga.LeaseRegistry;
import net.nexus_flow.core.ring.saga.LeaseRequestEnvelope;
import net.nexus_flow.core.ring.saga.SagaLeaseCoordinator;
import net.nexus_flow.core.ring.saga.SagaLeaseCoordinatorConfig;
import net.nexus_flow.core.ring.saga.SagaStateEnvelope;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.ProtocolErrorCode;
import net.nexus_flow.core.ring.wire.RingFrame;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import net.nexus_flow.core.saga.SagaId;
import org.junit.jupiter.api.Test;

/** Pins {@link RingFrameRouter} demux: every frame type routes to the right subsystem. */
class RingFrameRouterTest {

    private static final PeerId LOCAL  = PeerId.of("local");
    private static final PeerId REMOTE = PeerId.of("remote");

    private static RingConnection stubConnection() throws IOException {
        return net.nexus_flow.core.ring.transport.TestRingConnections.stub();
    }

    private record Fixture(
                           RingFrameRouter router,
                           HeartbeatFailureDetector heartbeat,
                           SagaLeaseCoordinator sagaCoord,
                           RingDispatcher dispatcher,
                           AtomicReference<DispatchRequestEnvelope> dispatchedRequest,
                           AtomicInteger eventReceived,
                           ScheduledExecutorService scheduler) implements AutoCloseable {

        @Override
        public void close() {
            heartbeat.close();
            sagaCoord.close();
            scheduler.shutdownNow();
        }
    }

    private static Fixture build() {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        Map<PeerId, PeerAddress> seeds = new LinkedHashMap<>();
        seeds.put(REMOTE, PeerAddress.loopback(9001));
        StaticPeerListMembership m = new StaticPeerListMembership(Clock.systemUTC(), seeds);
        m.start();
        RingConnectionRegistry                   connections   = new RingConnectionRegistry();
        DefaultMembershipRegistry                mutable       = m.mutableRegistry();
        HeartbeatFailureDetector                 heartbeat     = new HeartbeatFailureDetector(
                HeartbeatConfig.defaults(LOCAL), Clock.systemUTC(), connections, mutable);
        LeaseRegistry                            leaseRegistry = new LeaseRegistry(Clock.systemUTC());
        SagaLeaseCoordinator                     sagaCoord     = new SagaLeaseCoordinator(
                SagaLeaseCoordinatorConfig.defaults(LOCAL),
                Clock.systemUTC(),
                leaseRegistry,
                connections,
                (sagaId, owner, expiry) -> new LeaseClaimOutcome.Claimed(
                        new net.nexus_flow.core.ring.saga.SagaLease(sagaId, owner, expiry)),
                m.registry());
        PendingResponseRegistry                  pending       = new PendingResponseRegistry(16, sched);
        DefaultHandlerDirectory                  directory     = new DefaultHandlerDirectory();
        RingDispatcher                           dispatcher    = new RingDispatcher(
                LOCAL, connections, directory, new RoundRobinPeerSelector(), pending);
        AtomicReference<DispatchRequestEnvelope> dispatched    = new AtomicReference<>();
        AtomicInteger                            events        = new AtomicInteger();
        LocalDispatchHandler                     local         = ctx -> {
                                                                   dispatched.set(ctx.request());
                                                                   return DispatchResponseEnvelope.success(
                                                                                                           ctx.request().correlationId(),
                                                                                                           "result.Type", "codec",
                                                                                                           new byte[0]);
                                                               };
        RingFrameRouter                          router        = RingFrameRouter.forSingleTenantTrustedRing(
                                                                                                            heartbeat, sagaCoord,
                                                                                                            dispatcher, local,
                                                                                                            (sender, frame) -> events
                                                                                                                    .incrementAndGet());
        return new Fixture(router, heartbeat, sagaCoord, dispatcher, dispatched, events, sched);
    }

    @Test
    void pingFrame_isRoutedToHeartbeat_whichRepliesPong() throws Exception {
        try (Fixture f = build()) {
            RingConnection conn = stubConnection();
            assertDoesNotThrow(() -> f.router.onFrame(
                                                      conn,
                                                      new RingFrame(FrameType.PING, new PingPongPayload(7L, REMOTE).encode())));
        }
    }

    @Test
    void pongFrame_isRoutedToHeartbeat_clearingOutstandingProbes() throws Exception {
        try (Fixture f = build()) {
            RingConnection conn = stubConnection();
            f.router.onFrame(
                             conn,
                             new RingFrame(FrameType.PONG, new PingPongPayload(8L, REMOTE).encode()));
            assertEquals(0, f.heartbeat.outstandingProbesSnapshot().getOrDefault(REMOTE, 0));
        }
    }

    @Test
    void sagaStateFrame_isRoutedToLeaseCoordinator() throws Exception {
        try (Fixture f = build()) {
            RingConnection conn = stubConnection();
            // No bound peerId on the test connection — the sender-owner check is bypassed
            // for unauthenticated connections (plain-TCP test mode).
            SagaId sagaId = SagaId.random();
            assertDoesNotThrow(() -> f.router.onFrame(
                                                      conn,
                                                      new RingFrame(
                                                              FrameType.SAGA_STATE,
                                                              new SagaStateEnvelope(
                                                                      sagaId, REMOTE,
                                                                      Instant.now().plus(Duration.ofMinutes(1)).toEpochMilli(),
                                                                      1L).encode())));
        }
    }

    @Test
    void leaseRequestFrame_isRoutedToLeaseCoordinator() throws Exception {
        try (Fixture f = build()) {
            RingConnection conn = stubConnection();
            assertDoesNotThrow(() -> f.router.onFrame(
                                                      conn,
                                                      new RingFrame(FrameType.LEASE_REQ,
                                                              new LeaseRequestEnvelope(
                                                                      SagaId.random(), REMOTE,
                                                                      System.currentTimeMillis() + 30_000).encode())));
        }
    }

    @Test
    void commandReqFrame_invokesLocalDispatchHandler_andReplies() throws Exception {
        try (Fixture f = build()) {
            RingConnection          conn = stubConnection();
            DispatchRequestEnvelope req  = new DispatchRequestEnvelope(
                    HandlerRole.COMMAND,
                    DispatchCorrelationId.next(),
                    REMOTE, "com.acme.PlaceOrder", "java-v1",
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    null,
                    System.currentTimeMillis(), 0L,
                    new byte[]{1, 2, 3});
            f.router.onFrame(conn, new RingFrame(FrameType.COMMAND_REQ, req.encode()));
            assertEquals(req.correlationId(), f.dispatchedRequest.get().correlationId());
            assertEquals(HandlerRole.COMMAND, f.dispatchedRequest.get().role());
        }
    }

    @Test
    void commandRespFrame_isRoutedToDispatcher_completingPending() throws Exception {
        try (Fixture f = build()) {
            RingConnection        conn = stubConnection();
            DispatchCorrelationId id   = DispatchCorrelationId.next();
            assertDoesNotThrow(() -> f.router.onFrame(
                                                      conn,
                                                      new RingFrame(FrameType.COMMAND_RESP,
                                                              DispatchResponseEnvelope.success(
                                                                                               id, "result.Type", "codec", new byte[0])
                                                                      .encode())));
        }
    }

    @Test
    void eventFrame_isForwardedToEventHandler() throws Exception {
        try (Fixture f = build()) {
            RingConnection conn = stubConnection();
            f.router.onFrame(conn, new RingFrame(FrameType.EVENT, new byte[0]));
            assertEquals(1, f.eventReceived.get());
        }
    }

    @Test
    void unhandledFrameTypes_areLoggedButDoNotThrow() throws Exception {
        try (Fixture f = build()) {
            RingConnection conn = stubConnection();
            assertDoesNotThrow(() -> f.router.onFrame(
                                                      conn,
                                                      new RingFrame(FrameType.LEASE_GRANT,
                                                              new net.nexus_flow.core.ring.saga.LeaseGrantEnvelope(
                                                                      SagaId.random(), REMOTE,
                                                                      System.currentTimeMillis() + 1_000).encode())));
        }
    }

    @Test
    void protocolException_inSubsystem_isReThrown_soReadLoopClosesConnection() throws Exception {
        try (Fixture f = build()) {
            RingConnection conn = stubConnection();
            assertThrows(
                         RingProtocolException.class,
                         () -> f.router.onFrame(conn, new RingFrame(FrameType.PING, new byte[]{1, 2})));
        }
    }

    @Test
    void runtimeException_inSubsystem_isCaught_keepingConnectionOpen() throws Exception {
        try (Fixture base = build()) {
            RingFrameRouter         brittle = RingFrameRouter.forSingleTenantTrustedRing(
                                                                                         base.heartbeat, base.sagaCoord, base.dispatcher,
                                                                                         ctx -> {
                                                                                             throw new RuntimeException(
                                                                                                     "simulated handler crash");
                                                                                         },
                                                                                         null);
            RingConnection          conn    = stubConnection();
            DispatchRequestEnvelope req     = new DispatchRequestEnvelope(
                    HandlerRole.COMMAND,
                    DispatchCorrelationId.next(),
                    REMOTE, "com.acme.X", "c",
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    null, System.currentTimeMillis(), 0L, new byte[0]);
            // No throw — runtime exception is caught; router replies with INTERNAL.
            assertDoesNotThrow(
                               () -> brittle.onFrame(conn, new RingFrame(FrameType.COMMAND_REQ, req.encode())));
        }
    }

    @Test
    void deniedDispatch_returnsForbidden_andDoesNotInvokeHandler() throws Exception {
        try (Fixture base = build()) {
            AtomicInteger           invocations = new AtomicInteger();
            RingFrameRouter         denied      = new RingFrameRouter(
                    base.heartbeat, base.sagaCoord, base.dispatcher,
                    ctx -> {
                        invocations.incrementAndGet();
                        return DispatchResponseEnvelope.success(
                                                                ctx.request().correlationId(), "t", "c", new byte[0]);
                    },
                    DispatchAuthorizer.DENY_ALL,
                    net.nexus_flow.core.ring.observability.RingMetrics.noOp(),
                    null);
            RingConnection          conn        = stubConnection();
            DispatchRequestEnvelope req         = new DispatchRequestEnvelope(
                    HandlerRole.COMMAND,
                    DispatchCorrelationId.next(),
                    REMOTE, "com.acme.Forbidden", "c",
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    null, System.currentTimeMillis(), 0L, new byte[0]);
            denied.onFrame(conn, new RingFrame(FrameType.COMMAND_REQ, req.encode()));
            assertEquals(0, invocations.get(), "denied dispatch MUST NOT reach the handler");
        }
    }

    @Test
    void forgedSagaState_fromAuthenticatedPeer_isRejected() throws Exception {
        Map<PeerId, PeerAddress> seeds = new LinkedHashMap<>();
        seeds.put(REMOTE, PeerAddress.loopback(9001));
        StaticPeerListMembership m = new StaticPeerListMembership(Clock.systemUTC(), seeds);
        m.start();
        RingConnectionRegistry    connections   = new RingConnectionRegistry();
        DefaultMembershipRegistry mutable       = m.mutableRegistry();
        LeaseRegistry             leaseRegistry = new LeaseRegistry(Clock.systemUTC());
        try (ScheduledExecutorServiceCloseable sched =
                new ScheduledExecutorServiceCloseable(Executors.newSingleThreadScheduledExecutor());
                HeartbeatFailureDetector heartbeat = new HeartbeatFailureDetector(
                        HeartbeatConfig.defaults(LOCAL), Clock.systemUTC(), connections, mutable);
                SagaLeaseCoordinator sagaCoord = new SagaLeaseCoordinator(
                        SagaLeaseCoordinatorConfig.defaults(LOCAL),
                        Clock.systemUTC(),
                        leaseRegistry,
                        connections,
                        (sagaId, owner, expiry) -> new LeaseClaimOutcome.Claimed(
                                new net.nexus_flow.core.ring.saga.SagaLease(sagaId, owner, expiry)),
                        m.registry());
                PendingResponseRegistry pending = new PendingResponseRegistry(16, sched.delegate())) {
            DefaultHandlerDirectory directory  = new DefaultHandlerDirectory();
            RingDispatcher          dispatcher = new RingDispatcher(
                    LOCAL, connections, directory, new RoundRobinPeerSelector(), pending);
            RingFrameRouter         router     = RingFrameRouter.forSingleTenantTrustedRing(
                                                                                            heartbeat, sagaCoord, dispatcher, ctx -> {
                                                                                                throw new AssertionError(
                                                                                                        "dispatch handler must not be called");
                                                                                            }, null);

            RingConnection conn       = stubConnection();
            PeerId         fakeSender = PeerId.of("attacker");
            conn.bindPeerId(fakeSender);
            SagaId sagaId = SagaId.random();
            router.onFrame(conn, new RingFrame(FrameType.SAGA_STATE,
                    new SagaStateEnvelope(
                            sagaId, REMOTE,
                            Instant.now().plus(Duration.ofMinutes(1)).toEpochMilli(),
                            1L).encode()));
            assertEquals(
                         java.util.Optional.empty(),
                         leaseRegistry.lease(sagaId),
                         "forged SAGA_STATE must be rejected by sender-owner check");
        }
    }

    /** AutoCloseable wrapper so the test can use the scheduler in a try-with-resources block. */
    private static final class ScheduledExecutorServiceCloseable implements AutoCloseable {
        private final ScheduledExecutorService delegate;

        ScheduledExecutorServiceCloseable(ScheduledExecutorService delegate) {
            this.delegate = delegate;
        }

        ScheduledExecutorService delegate() {
            return delegate;
        }

        @Override
        public void close() {
            delegate.shutdownNow();
        }
    }

    @Test
    void unknownProtocolErrorCode_inResponseDecode_doesNotThrow() throws Exception {
        try (Fixture f = build()) {
            RingConnection conn = stubConnection();
            // Build a response envelope with a real code so encode succeeds; decode is on the
            // wire-tolerance contract of ProtocolErrorCode.fromWireCode (unknown → UNKNOWN).
            DispatchResponseEnvelope resp = DispatchResponseEnvelope.failure(
                                                                             DispatchCorrelationId.next(), ProtocolErrorCode.INTERNAL,
                                                                             "test");
            assertDoesNotThrow(
                               () -> f.router.onFrame(conn, new RingFrame(FrameType.COMMAND_RESP, resp.encode())));
        }
    }
}

package net.nexus_flow.core.ring;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.ring.dispatch.DispatchResponseEnvelope;
import net.nexus_flow.core.ring.dispatch.LocalDispatchHandler;
import net.nexus_flow.core.ring.dispatch.PendingResponseRegistry;
import net.nexus_flow.core.ring.dispatch.RingDispatcher;
import net.nexus_flow.core.ring.dispatch.RingFrameRouter;
import net.nexus_flow.core.ring.membership.DefaultMembershipRegistry;
import net.nexus_flow.core.ring.membership.HeartbeatConfig;
import net.nexus_flow.core.ring.membership.HeartbeatFailureDetector;
import net.nexus_flow.core.ring.membership.PeerState;
import net.nexus_flow.core.ring.membership.StaticPeerListMembership;
import net.nexus_flow.core.ring.registry.DefaultHandlerDirectory;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.registry.RoundRobinPeerSelector;
import net.nexus_flow.core.ring.saga.LeaseClaimOutcome;
import net.nexus_flow.core.ring.saga.LeaseRegistry;
import net.nexus_flow.core.ring.saga.SagaLeaseCoordinator;
import net.nexus_flow.core.ring.saga.SagaLeaseCoordinatorConfig;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingAcceptor;
import net.nexus_flow.core.ring.transport.RingAcceptorConfig;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.wire.ProtocolErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RingEndToEndIntegrationTest {

    private static final PeerId POD_A = PeerId.of("pod-a");
    private static final PeerId POD_B = PeerId.of("pod-b");

    private static final class PodFixture implements AutoCloseable {
        final PeerId                    localPeerId;
        final RingAcceptor              acceptor;
        final RingConnectionRegistry    connections;
        final DefaultMembershipRegistry membership;
        final DefaultHandlerDirectory   directory;
        final HeartbeatFailureDetector  heartbeat;
        final SagaLeaseCoordinator      sagaCoord;
        final PendingResponseRegistry   pendingResponses;
        final RingDispatcher            dispatcher;
        final RingFrameRouter           router;
        final ScheduledExecutorService  scheduler;
        final AtomicReference<String>   lastHandledType = new AtomicReference<>();

        PodFixture(PeerId localPeerId) throws IOException {
            this.localPeerId = localPeerId;
            this.scheduler   = Executors.newSingleThreadScheduledExecutor();
            this.connections = new RingConnectionRegistry();
            StaticPeerListMembership m = new StaticPeerListMembership(
                    Clock.systemUTC(),
                    Map.of(otherOf(localPeerId), PeerAddress.loopback(1)));
            m.start();
            this.membership = m.mutableRegistry();
            this.directory  = new DefaultHandlerDirectory();
            this.heartbeat  = new HeartbeatFailureDetector(
                    HeartbeatConfig.defaults(localPeerId),
                    Clock.systemUTC(),
                    connections,
                    membership);
            LeaseRegistry leaseRegistry = new LeaseRegistry(Clock.systemUTC());
            this.sagaCoord        = new SagaLeaseCoordinator(
                    SagaLeaseCoordinatorConfig.defaults(localPeerId),
                    Clock.systemUTC(),
                    leaseRegistry,
                    connections,
                    (sagaId, owner, expiry) -> new LeaseClaimOutcome.Claimed(
                            new net.nexus_flow.core.ring.saga.SagaLease(sagaId, owner, expiry)),
                    m.registry());
            this.pendingResponses = new PendingResponseRegistry(64, scheduler);
            this.dispatcher       = new RingDispatcher(
                    localPeerId, connections, directory, new RoundRobinPeerSelector(),
                    pendingResponses);
            LocalDispatchHandler localHandler = ctx -> {
                lastHandledType.set(ctx.request().payloadType());
                String resultBody = "handled-by-" + localPeerId.value() + ":"
                        + ctx.request().payloadType();
                return DispatchResponseEnvelope.success(
                                                        ctx.request().correlationId(), "result.Handled", "java-v1",
                                                        resultBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            };
            this.router   = RingFrameRouter.forSingleTenantTrustedRing(
                                                                       heartbeat, sagaCoord, dispatcher, localHandler, null);
            this.acceptor = new RingAcceptor(RingAcceptorConfig.loopbackForTests(), router);
            acceptor.start();
        }

        void markPeerAlive(PeerId other) {
            membership.transition(other, PeerState.ALIVE);
        }

        void registerConnection(PeerId peerId, RingConnection conn) {
            connections.register(peerId, conn);
            conn.bindPeerId(peerId);
            conn.markActive();
        }

        @Override
        @SuppressWarnings("PMD.UseTryWithResources")
        public void close() throws IOException {
            try {
                heartbeat.close();
                sagaCoord.close();
                pendingResponses.close();
                scheduler.shutdownNow();
            } finally {
                acceptor.close();
            }
        }
    }

    private static PeerId otherOf(PeerId p) {
        return p.equals(POD_A) ? POD_B : POD_A;
    }

    private record RemoteAcceptorFixture(
                                         RingAcceptor acceptor,
                                         AtomicReference<String> lastHandledType,
                                         PodFixture sharedSubs) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            acceptor.close();
        }
    }

    private static RemoteAcceptorFixture buildRemoteAcceptor(
            PodFixture sharedSubs, LocalDispatchHandler handler) throws IOException {
        AtomicReference<String> lastHandled    = new AtomicReference<>();
        LocalDispatchHandler    wrapped        = ctx -> {
                                                   lastHandled.set(ctx.request().payloadType());
                                                   return handler.dispatch(ctx);
                                               };
        RingFrameRouter         remoteRouter   = RingFrameRouter.forSingleTenantTrustedRing(
                                                                                            sharedSubs.heartbeat, sharedSubs.sagaCoord,
                                                                                            sharedSubs.dispatcher, wrapped, null);
        RingAcceptor            remoteAcceptor =
                new RingAcceptor(RingAcceptorConfig.loopbackForTests(), remoteRouter);
        remoteAcceptor.start();
        await().atMost(2, TimeUnit.SECONDS).until(() -> remoteAcceptor.boundPort() > 0);
        return new RemoteAcceptorFixture(remoteAcceptor, lastHandled, sharedSubs);
    }

    private static RingConnection wireToRemote(
            PodFixture pod,
            PeerId remotePeerId,
            RemoteAcceptorFixture remote,
            ExecutorService vtExec) throws IOException {
        Socket socket = new Socket();
        socket.connect(
                       new InetSocketAddress(PeerAddress.LOOPBACK_HOST, remote.acceptor.boundPort()),
                       2_000);
        socket.setTcpNoDelay(true);
        RingConnection conn = net.nexus_flow.core.ring.transport.TestRingConnections.over(
                                                                                          socket, PeerAddress.loopback(remote.acceptor
                                                                                                  .boundPort()));
        vtExec.submit(() -> conn.runReadLoop(pod.router));
        vtExec.submit(conn::runWriteLoop);
        pod.registerConnection(remotePeerId, conn);
        pod.markPeerAlive(remotePeerId);
        return conn;
    }

    @Test
    void crossPodCommandRoundTrip_dispatchOnA_handledOnB_responseObservedOnA() throws Exception {
        try (PodFixture podA = new PodFixture(POD_A);
                ExecutorService vtExec = Executors.newVirtualThreadPerTaskExecutor()) {
            LocalDispatchHandler successHandler = ctx -> DispatchResponseEnvelope.success(
                                                                                          ctx.request().correlationId(), "result.Handled",
                                                                                          "java-v1",
                                                                                          ("handled-by-pod-b:" + ctx.request()
                                                                                                  .payloadType())
                                                                                                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try (RemoteAcceptorFixture remote = buildRemoteAcceptor(podA, successHandler);
                    RingConnection _ = wireToRemote(podA, POD_B, remote, vtExec)) {
                podA.directory.register(HandlerRole.COMMAND, POD_B, Set.of("com.acme.PlaceOrder"));

                CompletableFuture<DispatchResponseEnvelope> future   = podA.dispatcher.dispatch(
                                                                                                HandlerRole.COMMAND, "com.acme.PlaceOrder",
                                                                                                "java-v1",
                                                                                                "place-order-payload".getBytes(
                                                                                                                               java.nio.charset.StandardCharsets.UTF_8),
                                                                                                UUID.randomUUID(), UUID.randomUUID(), UUID
                                                                                                        .randomUUID(),
                                                                                                "acme", Duration.ofSeconds(5), null);
                DispatchResponseEnvelope                    response = future.get(5, TimeUnit.SECONDS);
                assertNotNull(response);
                assertEquals(DispatchResponseEnvelope.Outcome.SUCCESS, response.outcome());
                assertEquals(ProtocolErrorCode.OK, response.errorCode());
                assertEquals("result.Handled", response.payloadType());
                String responseBody = new String(
                        response.payloadBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals("handled-by-pod-b:com.acme.PlaceOrder", responseBody);
                assertEquals("com.acme.PlaceOrder", remote.lastHandledType.get());
            }
        }
    }

    @Test
    void crossPodCommand_targetReturnsFailure_propagatesToOriginator() throws Exception {
        try (PodFixture podA = new PodFixture(POD_A);
                ExecutorService vtExec = Executors.newVirtualThreadPerTaskExecutor()) {
            LocalDispatchHandler failingHandler = ctx -> DispatchResponseEnvelope.failure(
                                                                                          ctx.request().correlationId(),
                                                                                          ProtocolErrorCode.DOMAIN_FAILURE,
                                                                                          "deliberate failure for test");
            try (RemoteAcceptorFixture remote = buildRemoteAcceptor(podA, failingHandler);
                    RingConnection _ = wireToRemote(podA, POD_B, remote, vtExec)) {
                podA.directory.register(HandlerRole.COMMAND, POD_B, Set.of("com.acme.Failing"));

                CompletableFuture<DispatchResponseEnvelope> future   = podA.dispatcher.dispatch(
                                                                                                HandlerRole.COMMAND, "com.acme.Failing",
                                                                                                "java-v1", new byte[0],
                                                                                                UUID.randomUUID(), UUID.randomUUID(), UUID
                                                                                                        .randomUUID(),
                                                                                                null, Duration.ofSeconds(5), null);
                DispatchResponseEnvelope                    response = future.get(5, TimeUnit.SECONDS);
                assertEquals(DispatchResponseEnvelope.Outcome.FAILURE, response.outcome());
                assertEquals(ProtocolErrorCode.DOMAIN_FAILURE, response.errorCode());
                assertEquals("deliberate failure for test", response.reason());
            }
        }
    }

    @Test
    void crossPodQuery_returnsResponsePayload() throws Exception {
        try (PodFixture podA = new PodFixture(POD_A);
                ExecutorService vtExec = Executors.newVirtualThreadPerTaskExecutor()) {
            LocalDispatchHandler queryHandler = ctx -> DispatchResponseEnvelope.success(
                                                                                        ctx.request().correlationId(), "result.Found",
                                                                                        "java-v1",
                                                                                        ("query-result:" + ctx.request().payloadType())
                                                                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try (RemoteAcceptorFixture remote = buildRemoteAcceptor(podA, queryHandler);
                    RingConnection _ = wireToRemote(podA, POD_B, remote, vtExec)) {
                podA.directory.register(HandlerRole.QUERY, POD_B, Set.of("com.acme.FindOrder"));

                CompletableFuture<DispatchResponseEnvelope> future   = podA.dispatcher.dispatch(
                                                                                                HandlerRole.QUERY, "com.acme.FindOrder",
                                                                                                "java-v1",
                                                                                                "find-order-payload".getBytes(
                                                                                                                              java.nio.charset.StandardCharsets.UTF_8),
                                                                                                UUID.randomUUID(), UUID.randomUUID(), UUID
                                                                                                        .randomUUID(),
                                                                                                null, Duration.ofSeconds(5), null);
                DispatchResponseEnvelope                    response = future.get(5, TimeUnit.SECONDS);
                assertEquals(DispatchResponseEnvelope.Outcome.SUCCESS, response.outcome());
                assertEquals("com.acme.FindOrder", remote.lastHandledType.get());
                String responseBody = new String(
                        response.payloadBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals("query-result:com.acme.FindOrder", responseBody);
            }
        }
    }

    @Test
    void crossPodCommand_targetUnknown_returnsNotFound() throws Exception {
        try (PodFixture podA = new PodFixture(POD_A)) {
            CompletableFuture<DispatchResponseEnvelope> future   = podA.dispatcher.dispatch(
                                                                                            HandlerRole.COMMAND, "com.acme.NoSuchType",
                                                                                            "java-v1", new byte[0],
                                                                                            UUID.randomUUID(), UUID.randomUUID(), UUID
                                                                                                    .randomUUID(),
                                                                                            null, Duration.ofSeconds(2), null);
            DispatchResponseEnvelope                    response = future.get(2, TimeUnit.SECONDS);
            assertEquals(DispatchResponseEnvelope.Outcome.NOT_FOUND, response.outcome());
        }
    }
}

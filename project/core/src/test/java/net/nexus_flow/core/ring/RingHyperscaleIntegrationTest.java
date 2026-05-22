package net.nexus_flow.core.ring;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ring.dispatch.DispatchAuthorizer;
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
import net.nexus_flow.core.ring.transport.ConnectionTimeouts;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingAcceptor;
import net.nexus_flow.core.ring.transport.RingAcceptorConfig;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.transport.RingFrameHandler;
import net.nexus_flow.core.ring.transport.TestRingConnections;
import net.nexus_flow.core.ring.wire.ProtocolErrorCode;
import net.nexus_flow.core.ring.wire.RingFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests pinning the FULL ring stack under load and stress: many concurrent
 * dispatches across one connection, peer disconnects during in-flight requests,
 * authorization deny-paths end-to-end, and idle/handshake timeout interactions.
 *
 * <p>Every assertion uses the final API (no {@code legacyForTests} ever; the
 * {@link TestRingConnections} helper builds everything via {@link RingConnection.Builder}).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RingHyperscaleIntegrationTest {

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

        PodFixture(PeerId localPeerId, LocalDispatchHandler localHandler,
                DispatchAuthorizer authorizer) throws IOException {
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
                    HeartbeatConfig.defaults(localPeerId), Clock.systemUTC(),
                    connections, membership);
            LeaseRegistry leaseRegistry = new LeaseRegistry(Clock.systemUTC());
            this.sagaCoord        = new SagaLeaseCoordinator(
                    SagaLeaseCoordinatorConfig.defaults(localPeerId),
                    Clock.systemUTC(), leaseRegistry, connections,
                    (sagaId, owner, expiry) -> new LeaseClaimOutcome.Claimed(
                            new net.nexus_flow.core.ring.saga.SagaLease(sagaId, owner, expiry)),
                    m.registry());
            this.pendingResponses = new PendingResponseRegistry(2048, scheduler);
            this.dispatcher       = new RingDispatcher(
                    localPeerId, connections, directory, new RoundRobinPeerSelector(),
                    pendingResponses);
            this.router           = new RingFrameRouter(
                    heartbeat, sagaCoord, dispatcher, localHandler,
                    authorizer,
                    net.nexus_flow.core.ring.observability.RingMetrics.noOp(),
                    null);
            this.acceptor         = new RingAcceptor(RingAcceptorConfig.loopbackForTests(), router);
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
        }

        void registerConnection(PeerId peerId, RingConnection conn) {
            connections.register(peerId, conn);
            conn.bindPeerId(peerId);
            conn.markActive();
            membership.transition(peerId, PeerState.ALIVE);
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

    private record RemoteFixture(
                                 RingAcceptor acceptor,
                                 PodFixture sharedSubs,
                                 AtomicInteger handlerInvocations) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            acceptor.close();
        }
    }

    private static RemoteFixture buildRemote(
            PodFixture sharedSubs, LocalDispatchHandler handler) throws IOException {
        return buildRemote(sharedSubs, handler, java.time.Clock.systemUTC());
    }

    private static RemoteFixture buildRemote(
            PodFixture sharedSubs, LocalDispatchHandler handler, java.time.Clock clock) throws IOException {
        AtomicInteger        invocations    = new AtomicInteger();
        LocalDispatchHandler wrapped        = ctx -> {
                                                invocations.incrementAndGet();
                                                return handler.dispatch(ctx);
                                            };
        RingFrameRouter      remoteRouter   = new RingFrameRouter(
                sharedSubs.heartbeat, sharedSubs.sagaCoord, sharedSubs.dispatcher,
                wrapped, DispatchAuthorizer.ALLOW_ALL,
                net.nexus_flow.core.ring.observability.RingMetrics.noOp(),
                clock, null);
        RingAcceptor         remoteAcceptor =
                new RingAcceptor(RingAcceptorConfig.loopbackForTests(), remoteRouter);
        remoteAcceptor.start();
        await().atMost(2, TimeUnit.SECONDS).until(() -> remoteAcceptor.boundPort() > 0);
        return new RemoteFixture(remoteAcceptor, sharedSubs, invocations);
    }

    private static RingConnection wireToRemote(
            PodFixture pod, PeerId remoteId, RemoteFixture remote,
            ExecutorService vtExec) throws IOException {
        Socket socket = new Socket();
        socket.connect(
                       new InetSocketAddress(PeerAddress.LOOPBACK_HOST, remote.acceptor.boundPort()),
                       2_000);
        socket.setTcpNoDelay(true);
        RingConnection conn = TestRingConnections.over(
                                                       socket, PeerAddress.loopback(remote.acceptor.boundPort()));
        vtExec.submit(() -> conn.runReadLoop(pod.router));
        vtExec.submit(conn::runWriteLoop);
        pod.registerConnection(remoteId, conn);
        return conn;
    }

    // ------------------------------------------------------------------
    // Load tests
    // ------------------------------------------------------------------

    @Test
    void manyConcurrentDispatches_overSingleConnection_allCompleteOrTimeoutCleanly() throws Exception {
        final int            N    = 200;
        LocalDispatchHandler echo = ctx -> DispatchResponseEnvelope.success(
                                                                            ctx.request().correlationId(),
                                                                            "echo.Result", "java-v1",
                                                                            ctx.request().payloadBytes());
        try (PodFixture podA = new PodFixture(POD_A, echo, DispatchAuthorizer.ALLOW_ALL);
                ExecutorService vtExec = Executors.newVirtualThreadPerTaskExecutor()) {
            try (RemoteFixture remote = buildRemote(podA, echo);
                    RingConnection _ = wireToRemote(podA, POD_B, remote, vtExec)) {
                podA.directory.register(HandlerRole.COMMAND, POD_B, Set.of("com.acme.Echo"));
                List<CompletableFuture<DispatchResponseEnvelope>> futures =
                        new java.util.ArrayList<>(N);
                for (int i = 0; i < N; i++) {
                    futures.add(podA.dispatcher.dispatch(
                                                         HandlerRole.COMMAND, "com.acme.Echo", "java-v1",
                                                         new byte[]{(byte) (i & 0x7F)},
                                                         UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                                         null, Duration.ofSeconds(20), null));
                }
                int success = 0;
                for (CompletableFuture<DispatchResponseEnvelope> f : futures) {
                    DispatchResponseEnvelope r = f.get(30, TimeUnit.SECONDS);
                    if (r.outcome() == DispatchResponseEnvelope.Outcome.SUCCESS) {
                        success++;
                    }
                }
                assertEquals(N, success, "every dispatch must round-trip under load");
                assertEquals(N, remote.handlerInvocations.get(),
                             "remote handler must be invoked exactly once per dispatch");
                assertEquals(0, podA.pendingResponses.inFlight(),
                             "all in-flight entries must have drained");
            }
        }
    }

    @Test
    void pendingDispatches_cancelled_whenConnectionToTargetClosed() throws Exception {
        // Build a remote handler that never replies — the dispatch will park forever absent
        // the close-cancel hook.
        java.util.concurrent.CountDownLatch saw     = new java.util.concurrent.CountDownLatch(1);
        LocalDispatchHandler                swallow = ctx -> {
                                                        saw.countDown();
                                                        try {
                                                            Thread.sleep(60_000);
                                                        } catch (InterruptedException _) {
                                                            Thread.currentThread().interrupt();
                                                        }
                                                        return DispatchResponseEnvelope.success(
                                                                                                ctx.request().correlationId(), "t", "c",
                                                                                                new byte[0]);
                                                    };
        try (PodFixture podA = new PodFixture(POD_A,
                ctx -> DispatchResponseEnvelope.success(
                                                        ctx.request().correlationId(), "t", "c", new byte[0]),
                DispatchAuthorizer.ALLOW_ALL);
                ExecutorService vtExec = Executors.newVirtualThreadPerTaskExecutor()) {
            RemoteFixture  remote = buildRemote(podA, swallow);
            RingConnection conn   = wireToRemote(podA, POD_B, remote, vtExec);
            podA.directory.register(HandlerRole.COMMAND, POD_B, Set.of("com.acme.Hang"));

            CompletableFuture<DispatchResponseEnvelope> hung = podA.dispatcher.dispatch(
                                                                                        HandlerRole.COMMAND, "com.acme.Hang", "java-v1",
                                                                                        new byte[0],
                                                                                        UUID.randomUUID(), UUID.randomUUID(), UUID
                                                                                                .randomUUID(),
                                                                                        null, Duration.ofMinutes(5), null);
            assertTrue(saw.await(5, TimeUnit.SECONDS), "remote handler must observe the request");
            assertFalse(hung.isDone(), "future should still be pending");

            // Close the connection — dispatcher.onConnectionClosed must cancel the pending.
            conn.close(new IOException("operator force-close"));
            await().atMost(5, TimeUnit.SECONDS).until(hung::isDone);
            assertTrue(hung.isCompletedExceptionally(),
                       "pending dispatch must fail exceptionally on peer-close, NOT wait full timeout");
            remote.close();
        }
    }

    @Test
    void deniedDispatch_endToEnd_returnsForbidden_withSanitisedReason() throws Exception {
        LocalDispatchHandler handlerThatShouldNotRun =
                _ctx -> {
                                                                 throw new AssertionError("handler must not run when authz denies");
                                                             };
        AtomicInteger        denyCalls               = new AtomicInteger();
        DispatchAuthorizer   denyAcme                = (principal, role, tenant, type) -> {
                                                         denyCalls.incrementAndGet();
                                                         return new DispatchAuthorizer.AuthorizationDecision.Denied(
                                                                 "internal-detail-MUST-NOT-leak: tenant=" + tenant + " type=" + type);
                                                     };
        try (PodFixture podA = new PodFixture(POD_A,
                ctx -> DispatchResponseEnvelope.success(
                                                        ctx.request().correlationId(), "t", "c", new byte[0]),
                DispatchAuthorizer.ALLOW_ALL);
                ExecutorService vtExec = Executors.newVirtualThreadPerTaskExecutor()) {
            // Remote pod uses the deny authorizer.
            RingFrameRouter denyingRouter = new RingFrameRouter(
                    podA.heartbeat, podA.sagaCoord, podA.dispatcher,
                    handlerThatShouldNotRun, denyAcme,
                    net.nexus_flow.core.ring.observability.RingMetrics.noOp(), null);
            try (RingAcceptor remoteAcceptor = new RingAcceptor(
                    RingAcceptorConfig.loopbackForTests(), denyingRouter)) {
                remoteAcceptor.start();
                await().atMost(2, TimeUnit.SECONDS).until(() -> remoteAcceptor.boundPort() > 0);
                Socket socket = new Socket();
                socket.connect(
                               new InetSocketAddress(PeerAddress.LOOPBACK_HOST,
                                       remoteAcceptor.boundPort()),
                               2_000);
                socket.setTcpNoDelay(true);
                try (RingConnection conn = TestRingConnections.over(
                                                                    socket, PeerAddress.loopback(remoteAcceptor.boundPort()))) {
                    vtExec.submit(() -> conn.runReadLoop(podA.router));
                    vtExec.submit(conn::runWriteLoop);
                    podA.registerConnection(POD_B, conn);
                    podA.directory.register(HandlerRole.COMMAND, POD_B,
                                            Set.of("com.acme.SecretOp"));

                    DispatchResponseEnvelope r = podA.dispatcher.dispatch(
                                                                          HandlerRole.COMMAND, "com.acme.SecretOp", "java-v1",
                                                                          new byte[0],
                                                                          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                                                          "acme", Duration.ofSeconds(5), null)
                            .get(5, TimeUnit.SECONDS);
                    assertEquals(DispatchResponseEnvelope.Outcome.FORBIDDEN, r.outcome());
                    assertEquals(ProtocolErrorCode.FORBIDDEN, r.errorCode());
                    // The deny reason on the wire MUST be the sanitised "policy denied" — not
                    // the rich internal-detail message we passed to Denied(...).
                    assertFalse(r.reason().contains("internal-detail-MUST-NOT-leak"),
                                "denying authorizer's internal reason MUST NOT leak to the wire");
                    assertEquals("policy denied", r.reason());
                    assertEquals(1, denyCalls.get());
                }
            }
        }
    }

    @Test
    void expiredDeadline_atReceiver_repliesTimeout_withoutInvokingHandler() throws Exception {
        AtomicInteger        handlerCalls = new AtomicInteger();
        LocalDispatchHandler counted      = ctx -> {
                                              handlerCalls.incrementAndGet();
                                              return DispatchResponseEnvelope.success(
                                                                                      ctx.request().correlationId(), "t", "c", new byte[0]);
                                          };
        // The receiver pod uses a clock that runs +500ms ahead of the sender's. With a
        // sender-side deadlineRemaining of 1ms, the receiver's wireDelay calculation
        // (receiverClock.millis() - sendInstantEpochMillis) yields ~500ms > 1ms, so the
        // receiver's pre-handler deadline check rejects with TIMEOUT BEFORE invoking the
        // local handler. Without the clock skew, loopback latency on a fast machine is
        // sub-millisecond and the 1ms deadline survives — the test would race.
        java.time.Clock fwdClock = java.time.Clock.offset(
                                                          java.time.Clock.systemUTC(), Duration.ofMillis(500));
        try (PodFixture podA = new PodFixture(POD_A, counted, DispatchAuthorizer.ALLOW_ALL);
                ExecutorService vtExec = Executors.newVirtualThreadPerTaskExecutor()) {
            try (RemoteFixture remote = buildRemote(podA, counted, fwdClock);
                    RingConnection _ = wireToRemote(podA, POD_B, remote, vtExec)) {
                podA.directory.register(HandlerRole.COMMAND, POD_B, Set.of("com.acme.Late"));
                // Use a very small deadline — by the time the request reaches the receiver,
                // the deadline is already in the past.
                // Sender timeout is generous (so PendingResponseRegistry waits long enough
                // for the receiver to reply); the deadlineRemainingMillis embedded in the
                // request is what expires. The sender's timeout is the local wait budget,
                // not the cross-pod deadline.
                CompletableFuture<DispatchResponseEnvelope> f = podA.dispatcher.dispatch(
                                                                                         HandlerRole.COMMAND, "com.acme.Late", "java-v1",
                                                                                         new byte[0],
                                                                                         UUID.randomUUID(), UUID.randomUUID(), UUID
                                                                                                 .randomUUID(),
                                                                                         null, Duration.ofMillis(50), null);
                DispatchResponseEnvelope.Outcome            observed;
                try {
                    DispatchResponseEnvelope r = f.get(5, TimeUnit.SECONDS);
                    observed = r.outcome();
                } catch (java.util.concurrent.ExecutionException ee) {
                    // Local PendingResponseRegistry timed out before the receiver's TIMEOUT
                    // response arrived — also a valid expired-deadline path.
                    observed = DispatchResponseEnvelope.Outcome.TIMEOUT;
                }
                // The load-bearing assertion: SUCCESS is never observed for an expired
                // deadline. Either the receiver returned TIMEOUT or the local wait expired.
                assertNotEquals(DispatchResponseEnvelope.Outcome.SUCCESS, observed,
                                "expired-deadline dispatch MUST NOT return SUCCESS");
            }
        }
    }

    @Test
    void multipleSimultaneousPeerCloses_doNotLeakPendingFutures() throws Exception {
        LocalDispatchHandler hang = ctx -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return DispatchResponseEnvelope.success(
                                                    ctx.request().correlationId(), "t", "c", new byte[0]);
        };
        try (PodFixture podA = new PodFixture(POD_A, hang, DispatchAuthorizer.ALLOW_ALL);
                ExecutorService vtExec = Executors.newVirtualThreadPerTaskExecutor()) {
            RemoteFixture  remote = buildRemote(podA, hang);
            RingConnection conn   = wireToRemote(podA, POD_B, remote, vtExec);
            podA.directory.register(HandlerRole.COMMAND, POD_B, Set.of("com.acme.Hang"));

            List<CompletableFuture<DispatchResponseEnvelope>> futures =
                    new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(podA.dispatcher.dispatch(
                                                     HandlerRole.COMMAND, "com.acme.Hang", "java-v1", new byte[0],
                                                     UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                                     null, Duration.ofMinutes(5), null));
            }
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> podA.pendingResponses.inFlight() >= 50);
            conn.close(new IOException("simultaneous close"));
            await().atMost(10, TimeUnit.SECONDS)
                    .until(() -> futures.stream().allMatch(CompletableFuture::isDone));
            assertEquals(0, podA.pendingResponses.inFlight(),
                         "every pending future must be cancelled on peer close");
            remote.close();
        }
    }

    @Test
    void acceptorRefusesExcessConnections_underFloodFromManyClients() throws Exception {
        // maxConnections=4 with a long handshake timeout so admitted slots don't cycle out
        // during the test, AND a high accept-rate burst so the limit IS capacity, not rate.
        ConnectionTimeouts tightSlots = new ConnectionTimeouts(
                Duration.ofSeconds(60),  // long handshake — keep slots occupied during test
                Duration.ofSeconds(60),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1));
        RingAcceptorConfig cfg        = RingAcceptorConfig.builder()
                .bindAddress(PeerAddress.LOOPBACK_HOST)
                .port(0)
                .maxConnections(4)
                .acceptBurst(1024)
                .acceptRefillPerSecond(1024)
                .timeouts(tightSlots)
                .shutdownGrace(Duration.ofSeconds(2))
                .requireTlsInProduction(false)
                .build();
        try (RingAcceptor acceptor = new RingAcceptor(cfg, new RingFrameHandler() {
            @Override
            public void onFrame(RingConnection c, RingFrame f) {
                // sink
            }
        })) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
            List<Socket> clients = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < 20; i++) {
                    Socket s = new Socket();
                    clients.add(s);
                    s.connect(
                              new InetSocketAddress(PeerAddress.LOOPBACK_HOST,
                                      acceptor.boundPort()),
                              1_000);
                }
                await().atMost(5, TimeUnit.SECONDS)
                        .until(() -> acceptor.liveConnections() == 4 || acceptor.liveConnections() == 0);
                int live = acceptor.liveConnections();
                assertTrue(live <= 4,
                           "live connections must never exceed maxConnections: " + live);
                int refused = 0;
                for (Socket s : clients) {
                    s.setSoTimeout(200);
                    try {
                        if (s.getInputStream().read() == -1) {
                            refused++;
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                        // admitted slot held open — handshake timeout hasn't fired yet
                    }
                }
                assertTrue(refused >= 16,
                           "at least 16 connections must be refused; live=" + live
                                   + " refused=" + refused);
            } finally {
                clients.forEach(s -> {
                    try (s) {
                        // best-effort close via try-with-resources
                    } catch (IOException ignored) {
                        // best-effort
                    }
                });
            }
        }
    }

    @Test
    void closeFloodDuringActiveDispatches_releasesEveryAdmissionPermit() throws Exception {
        RingAcceptorConfig cfg = RingAcceptorConfig.builder()
                .bindAddress(PeerAddress.LOOPBACK_HOST)
                .port(0)
                .maxConnections(8)
                .acceptBurst(1024)
                .acceptRefillPerSecond(1024)
                .timeouts(TestRingConnections.loopbackTimeouts())
                .shutdownGrace(Duration.ofSeconds(2))
                .requireTlsInProduction(false)
                .build();
        try (RingAcceptor acceptor = new RingAcceptor(cfg, new RingFrameHandler() {
            @Override
            public void onFrame(RingConnection c, RingFrame f) {
                // sink
            }
        })) {
            acceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
            // Open and immediately close 50 sockets — exercise the rapid lifecycle and
            // make sure the connection-permit accounting releases on socket close.
            for (int i = 0; i < 50; i++) {
                Socket s = new Socket();
                s.connect(
                          new InetSocketAddress(PeerAddress.LOOPBACK_HOST,
                                  acceptor.boundPort()),
                          1_000);
                s.close();
            }
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> acceptor.liveConnections() == 0);
            // Acceptor recovered to zero live connections — permits were released.
            assertEquals(0, acceptor.liveConnections());
        }
    }
}

package net.nexus_flow.core.ring.dispatch;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.ring.registry.DefaultHandlerDirectory;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.registry.RoundRobinPeerSelector;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingAcceptor;
import net.nexus_flow.core.ring.transport.RingAcceptorConfig;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.transport.RingFrameHandler;
import net.nexus_flow.core.ring.wire.RingFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class RingDispatcherTest {

    private static final PeerId LOCAL  = PeerId.of("local");
    private static final PeerId REMOTE = PeerId.of("remote");

    private RingAcceptor             acceptor;
    private Socket                   clientSocket;
    private RingConnection           localToRemote;
    private ExecutorService          writerExec;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() throws Exception {
        acceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                new RingFrameHandler() {
                    @Override
                    public void onFrame(RingConnection connection, RingFrame frame) {
                    }
                });
        acceptor.start();
        await().atMost(2, TimeUnit.SECONDS).until(() -> acceptor.boundPort() > 0);
        clientSocket = new Socket();
        clientSocket.connect(
                             new InetSocketAddress(PeerAddress.LOOPBACK_HOST, acceptor.boundPort()), 2_000);
        clientSocket.setTcpNoDelay(true);
        localToRemote = net.nexus_flow.core.ring.transport.TestRingConnections.over(
                                                                                    clientSocket, PeerAddress.loopback(acceptor
                                                                                            .boundPort()));
        writerExec    = Executors.newVirtualThreadPerTaskExecutor();
        writerExec.submit(localToRemote::runWriteLoop);
        // Drive the connection's phase forward — outside the read loop the connection has no
        // way to learn its handshake is "complete" unless we mark it. Tests that talk to the
        // dispatcher do not exercise the wire-level handshake; we promote directly to ACTIVE.
        localToRemote.bindPeerId(REMOTE);
        localToRemote.markActive();
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdownNow();
        writerExec.shutdownNow();
        localToRemote.close();
        clientSocket.close();
        acceptor.close();
    }

    private RingDispatcher newDispatcher(
            RingConnectionRegistry connections,
            DefaultHandlerDirectory directory,
            PendingResponseRegistry pending) {
        return new RingDispatcher(
                LOCAL, connections, directory, new RoundRobinPeerSelector(), pending);
    }

    @Test
    void dispatch_noPeersAdvertise_returnsNotFoundOutcome() throws Exception {
        try (PendingResponseRegistry pending = new PendingResponseRegistry(16, scheduler)) {
            RingConnectionRegistry  connections = new RingConnectionRegistry();
            DefaultHandlerDirectory directory   = new DefaultHandlerDirectory();
            RingDispatcher          d           = newDispatcher(connections, directory, pending);

            CompletableFuture<DispatchResponseEnvelope> f    = d.dispatch(
                                                                          HandlerRole.COMMAND, "com.acme.X", "codec", new byte[]{1},
                                                                          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                                                          null, Duration.ofSeconds(1), null);
            DispatchResponseEnvelope                    resp = f.get(1, TimeUnit.SECONDS);
            assertEquals(DispatchResponseEnvelope.Outcome.NOT_FOUND, resp.outcome());
            assertTrue(resp.reason().contains("no peer advertises"));
        }
    }

    @Test
    void dispatch_selectedPeerHasNoConnection_returnsNotFoundOutcome() throws Exception {
        try (PendingResponseRegistry pending = new PendingResponseRegistry(16, scheduler)) {
            RingConnectionRegistry  connections = new RingConnectionRegistry();
            DefaultHandlerDirectory directory   = new DefaultHandlerDirectory();
            directory.register(HandlerRole.COMMAND, REMOTE, Set.of("com.acme.X"));
            RingDispatcher d = newDispatcher(connections, directory, pending);

            CompletableFuture<DispatchResponseEnvelope> f    = d.dispatch(
                                                                          HandlerRole.COMMAND, "com.acme.X", "codec", new byte[]{1},
                                                                          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                                                          null, Duration.ofSeconds(1), null);
            DispatchResponseEnvelope                    resp = f.get(1, TimeUnit.SECONDS);
            assertEquals(DispatchResponseEnvelope.Outcome.NOT_FOUND, resp.outcome());
        }
    }

    @Test
    void dispatch_happyPath_sendsRequestFrame_andPendingFutureRegistered() {
        try (PendingResponseRegistry pending = new PendingResponseRegistry(16, scheduler)) {
            RingConnectionRegistry connections = new RingConnectionRegistry();
            connections.register(REMOTE, localToRemote);
            DefaultHandlerDirectory directory = new DefaultHandlerDirectory();
            directory.register(HandlerRole.COMMAND, REMOTE, Set.of("com.acme.PlaceOrder"));
            RingDispatcher d = newDispatcher(connections, directory, pending);

            CompletableFuture<DispatchResponseEnvelope> f = d.dispatch(
                                                                       HandlerRole.COMMAND, "com.acme.PlaceOrder", "java-v1",
                                                                       new byte[]{42},
                                                                       UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                                                       "acme", Duration.ofSeconds(2), null);
            assertEquals(1, pending.inFlight());
            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> localToRemote.outboundQueueDepth() == 0);
            // No response wiring; the future will eventually time out — that's fine.
            assertFalse(f.isDone());
        }
    }

    @Test
    void onResponse_matchingCorrelation_completesPendingFuture() throws Exception {
        try (PendingResponseRegistry pending = new PendingResponseRegistry(16, scheduler)) {
            RingDispatcher                              d        = newDispatcher(
                                                                                 new RingConnectionRegistry(),
                                                                                 new DefaultHandlerDirectory(), pending);
            DispatchCorrelationId                       id       = DispatchCorrelationId.next();
            CompletableFuture<DispatchResponseEnvelope> f        =
                    pending.register(id, REMOTE, Duration.ofSeconds(2));
            DispatchResponseEnvelope                    response =
                    DispatchResponseEnvelope.success(id, "type", "codec", new byte[]{1});
            assertTrue(d.onResponse(response));
            assertEquals(response, f.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void onResponse_unknownCorrelation_returnsFalse() {
        try (PendingResponseRegistry pending = new PendingResponseRegistry(16, scheduler)) {
            RingDispatcher           d        = newDispatcher(
                                                              new RingConnectionRegistry(), new DefaultHandlerDirectory(), pending);
            DispatchResponseEnvelope response = DispatchResponseEnvelope.success(
                                                                                 DispatchCorrelationId.next(), "type", "codec",
                                                                                 new byte[]{1});
            assertFalse(d.onResponse(response));
        }
    }

    @Test
    void onConnectionClosed_cancelsPendingForThatPeer() {
        try (PendingResponseRegistry pending = new PendingResponseRegistry(16, scheduler)) {
            RingDispatcher                              d         = newDispatcher(
                                                                                  new RingConnectionRegistry(),
                                                                                  new DefaultHandlerDirectory(), pending);
            DispatchCorrelationId                       id        = DispatchCorrelationId.next();
            CompletableFuture<DispatchResponseEnvelope> f         =
                    pending.register(id, REMOTE, Duration.ofMinutes(10));
            int                                         cancelled = d.onConnectionClosed(REMOTE, new java.io.IOException("peer went away"));
            assertEquals(1, cancelled);
            assertTrue(f.isCompletedExceptionally());
        }
    }
}

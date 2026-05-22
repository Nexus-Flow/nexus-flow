package net.nexus_flow.core.ring;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.ring.dispatch.CommandPayloadCodec;
import net.nexus_flow.core.ring.dispatch.DispatchResponseEnvelope;
import net.nexus_flow.core.ring.dispatch.JavaSerializationCommandPayloadCodec;
import net.nexus_flow.core.ring.dispatch.LocalDispatchHandler;
import net.nexus_flow.core.ring.dispatch.PendingResponseRegistry;
import net.nexus_flow.core.ring.dispatch.RingCommandFallback;
import net.nexus_flow.core.ring.dispatch.RingDispatcher;
import net.nexus_flow.core.ring.dispatch.RingFrameRouter;
import net.nexus_flow.core.ring.membership.DefaultMembershipRegistry;
import net.nexus_flow.core.ring.membership.HeartbeatConfig;
import net.nexus_flow.core.ring.membership.HeartbeatFailureDetector;
import net.nexus_flow.core.ring.membership.PeerState;
import net.nexus_flow.core.ring.membership.StaticPeerListMembership;
import net.nexus_flow.core.ring.registry.DefaultHandlerDirectory;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.registry.HashRingPeerSelector;
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
import net.nexus_flow.core.ring.transport.TestRingConnections;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pin {@link RingCommandFallback}: a command whose payload type has NO local handler is
 * transparently routed cross-pod via {@link RingDispatcher}. Exercises the full final
 * stack — codec, dispatcher, router, authorizer, hash-ring selector for aggregate
 * affinity — without intermediate test helpers that bypass it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RingCommandFallbackIntegrationTest {

    private static final PeerId LOCAL_PEER  = PeerId.of("pod-local");
    private static final PeerId REMOTE_PEER = PeerId.of("pod-remote");

    /** Record-shaped command payload — Serializable transparently via record contract. */
    record CalculateTax(String orderId, long amountCents) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** Local-side helpers used by the test fixture. */
    private static final class Fixture implements AutoCloseable {
        final FlowRuntime              runtime;
        final RingAcceptor             remoteAcceptor;
        final RingConnection           localToRemote;
        final RingConnectionRegistry   connections;
        final PendingResponseRegistry  pending;
        final ScheduledExecutorService scheduler;
        final ExecutorService          vtExec;
        final HeartbeatFailureDetector heartbeat;
        final SagaLeaseCoordinator     sagaCoord;
        final AtomicInteger            remoteHandlerInvocations = new AtomicInteger();

        Fixture(LocalDispatchHandler remoteHandler) throws IOException {
            this.runtime     = FlowRuntime.builder().build();
            this.scheduler   = Executors.newSingleThreadScheduledExecutor();
            this.vtExec      = Executors.newVirtualThreadPerTaskExecutor();
            this.connections = new RingConnectionRegistry();

            // Local membership — knows about REMOTE_PEER.
            StaticPeerListMembership m = new StaticPeerListMembership(
                    Clock.systemUTC(),
                    Map.of(REMOTE_PEER, PeerAddress.loopback(1)));
            m.start();
            DefaultMembershipRegistry membership = m.mutableRegistry();
            this.heartbeat = new HeartbeatFailureDetector(
                    HeartbeatConfig.defaults(LOCAL_PEER), Clock.systemUTC(), connections, membership);
            this.sagaCoord = new SagaLeaseCoordinator(
                    SagaLeaseCoordinatorConfig.defaults(LOCAL_PEER), Clock.systemUTC(),
                    new LeaseRegistry(Clock.systemUTC()), connections,
                    (sagaId, owner, expiry) -> new LeaseClaimOutcome.Claimed(
                            new net.nexus_flow.core.ring.saga.SagaLease(sagaId, owner, expiry)),
                    m.registry());
            this.pending   = new PendingResponseRegistry(64, scheduler);
            // Use hash-ring selector so the affinity-routing contract is also exercised.
            RingDispatcher ringDispatcher = new RingDispatcher(
                    LOCAL_PEER, connections, new DefaultHandlerDirectory(),
                    new HashRingPeerSelector(), pending);
            // Remote pod: a standalone acceptor running the supplied handler.
            LocalDispatchHandler trackedHandler = ctx -> {
                                                    remoteHandlerInvocations.incrementAndGet();
                                                    return remoteHandler.dispatch(ctx);
                                                };
            RingFrameRouter      remoteRouter   = RingFrameRouter.forSingleTenantTrustedRing(
                                                                                             heartbeat, sagaCoord, ringDispatcher,
                                                                                             trackedHandler, null);
            this.remoteAcceptor = new RingAcceptor(
                    RingAcceptorConfig.loopbackForTests(), remoteRouter);
            remoteAcceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> remoteAcceptor.boundPort() > 0);

            Socket socket = new Socket();
            socket.connect(
                           new InetSocketAddress(PeerAddress.LOOPBACK_HOST, remoteAcceptor.boundPort()),
                           2_000);
            socket.setTcpNoDelay(true);
            this.localToRemote = TestRingConnections.over(socket,
                                                          PeerAddress.loopback(remoteAcceptor.boundPort()));
            // Local-side router is the same — we only use the dispatcher.
            RingFrameRouter localRouter = RingFrameRouter.forSingleTenantTrustedRing(
                                                                                     heartbeat, sagaCoord, ringDispatcher,
                                                                                     ctx -> DispatchResponseEnvelope.success(
                                                                                                                             ctx.request()
                                                                                                                                     .correlationId(),
                                                                                                                             "t", "c",
                                                                                                                             new byte[0]),
                                                                                     null);
            vtExec.submit(() -> localToRemote.runReadLoop(localRouter));
            vtExec.submit(localToRemote::runWriteLoop);
            connections.register(REMOTE_PEER, localToRemote);
            localToRemote.bindPeerId(REMOTE_PEER);
            localToRemote.markActive();
            membership.transition(REMOTE_PEER, PeerState.ALIVE);

            // Register the remote peer as the handler authority for our command type.
            DefaultHandlerDirectory directory = new DefaultHandlerDirectory();
            directory.register(HandlerRole.COMMAND, REMOTE_PEER, Set.of(CalculateTax.class.getName()));
            // Wire the dispatcher to that directory.
            // NB: we expose this via the field below for test usage.
            this.directory  = directory;
            this.dispatcher = new RingDispatcher(
                    LOCAL_PEER, connections, directory, new HashRingPeerSelector(), pending);
        }

        final DefaultHandlerDirectory directory;
        final RingDispatcher          dispatcher;

        @Override
        public void close() {
            try {
                localToRemote.close();
            } catch (Exception ignored) {
                /* best-effort */ }
            try {
                remoteAcceptor.close();
            } catch (Exception ignored) {
                /* best-effort */ }
            try {
                heartbeat.close();
            } catch (Exception ignored) {
                /* best-effort */ }
            try {
                sagaCoord.close();
            } catch (Exception ignored) {
                /* best-effort */ }
            pending.close();
            scheduler.shutdownNow();
            vtExec.shutdownNow();
            runtime.close();
        }
    }

    @Test
    void unknownCommand_isRoutedCrossPod_remoteHandlerExecutes() throws Exception {
        // Remote pod returns SUCCESS for every CalculateTax request.
        CommandPayloadCodec  codec      = new JavaSerializationCommandPayloadCodec();
        LocalDispatchHandler remoteEcho = ctx -> {
                                            CalculateTax decoded = codec.decode(ctx.request().payloadBytes(), CalculateTax.class);
                                            byte[]       body    = codec.encode(new TaxComputed(decoded.orderId(), decoded
                                                    .amountCents() / 10));
                                            return DispatchResponseEnvelope.success(ctx.request().correlationId(),
                                                                                    TaxComputed.class.getName(),
                                                                                    JavaSerializationCommandPayloadCodec.CODEC_ID, body);
                                        };
        try (Fixture fixture = new Fixture(remoteEcho)) {
            CommandBus          localBus = fixture.runtime.commands();
            RingCommandFallback fallback = RingCommandFallback.builder()
                    .localBus(localBus)
                    .ringDispatcher(fixture.dispatcher)
                    .payloadCodec(codec)
                    .build();

            CalculateTax          tax     = new CalculateTax("order-42", 10_000);
            Command<CalculateTax> command = Command.<CalculateTax>builder()
                    .body(tax).build();

            DispatchResult<Object> result = fallback.dispatchAndReturnResult(
                                                                             command, ExecutionContext.root(), Duration.ofSeconds(5));

            assertTrue(result instanceof DispatchResult.Success,
                       "cross-pod dispatch must succeed; got " + result);
            assertEquals(1, fixture.remoteHandlerInvocations.get(),
                         "remote handler must be invoked exactly once for the cross-pod command");
        }
    }

    /** Inner record returned by the remote handler — illustrates a typed response payload. */
    record TaxComputed(String orderId, long taxCents) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    @Test
    void commandWithLocalHandler_doesNotRouteCrossPod() throws Exception {
        try (Fixture fixture = new Fixture(
                ctx -> {
                    throw new AssertionError("remote handler MUST NOT be called when local exists");
                })) {
            AtomicInteger localCalls = new AtomicInteger();
            fixture.runtime.commands().register(
                                                new AbstractReturnCommandHandler<CalculateTax, Long>() {
                                                    @Override
                                                    protected Long handle(CalculateTax command) {
                                                        localCalls.incrementAndGet();
                                                        return command.amountCents() / 10L;
                                                    }
                                                });
            CommandPayloadCodec codec    = new JavaSerializationCommandPayloadCodec();
            RingCommandFallback fallback = RingCommandFallback.builder()
                    .localBus(fixture.runtime.commands())
                    .ringDispatcher(fixture.dispatcher)
                    .payloadCodec(codec)
                    .build();

            Command<CalculateTax> command = Command.<CalculateTax>builder()
                    .body(new CalculateTax("o-1", 1_000)).build();
            DispatchResult<Long>  result  = fallback.dispatchAndReturnResult(
                                                                             command, ExecutionContext.root(), Duration.ofSeconds(5));

            assertTrue(result instanceof DispatchResult.Success,
                       "local dispatch must succeed; got " + result);
            assertEquals(1, localCalls.get(),
                         "local handler MUST be the one executed when registered locally");
            assertEquals(0, fixture.remoteHandlerInvocations.get(),
                         "remote MUST NOT be consulted when local handler exists");
        }
    }

    @Test
    void codec_roundTrips_complexNestedRecords() {
        record Address(String street, String city) implements Serializable {
            @Serial
            private static final long serialVersionUID = 1L;
        }
        record Customer(String id, Address billing, java.util.List<String> tags)
                implements Serializable {
            @Serial
            private static final long serialVersionUID = 1L;
        }
        CommandPayloadCodec codec    = new JavaSerializationCommandPayloadCodec();
        Customer            original = new Customer(
                "cust-1",
                new Address("123 Main", "Madrid"),
                java.util.List.of("vip", "loyalty"));
        byte[]              encoded  = codec.encode(original);
        Customer            decoded  = codec.decode(encoded, Customer.class);
        assertEquals(original, decoded);
    }
}

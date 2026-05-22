package net.nexus_flow.core.ring.event;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Serial;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxAppender;
import net.nexus_flow.core.outbox.OutboxStatus;
import net.nexus_flow.core.ring.membership.PeerState;
import net.nexus_flow.core.ring.membership.StaticPeerListMembership;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingAcceptor;
import net.nexus_flow.core.ring.transport.RingAcceptorConfig;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.transport.RingFrameHandler;
import net.nexus_flow.core.ring.wire.RingFrame;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class RingOutboxBridgeTest {

    private static final PeerId LOCAL  = PeerId.of("local");
    private static final PeerId REMOTE = PeerId.of("remote");
    private static final Clock  T0     =
            Clock.fixed(Instant.parse("2026-05-25T10:00:00Z"), ZoneOffset.UTC);

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class TickAgg extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void tick() {
            recordEvent(new Tick("agg-1"));
        }
    }

    @Test
    void drainOnce_sendsEventFrameToEveryAlivePeer_andAdvancesCursor() throws Exception {
        try (RingAcceptor peerAcceptor = new RingAcceptor(
                RingAcceptorConfig.loopbackForTests(),
                new RingFrameHandler() {
                    @Override
                    public void onFrame(RingConnection connection, RingFrame frame) {
                    }
                })) {
            peerAcceptor.start();
            await().atMost(2, TimeUnit.SECONDS).until(() -> peerAcceptor.boundPort() > 0);

            try (Socket socket = new Socket();
                    ExecutorService writerExec = Executors.newVirtualThreadPerTaskExecutor()) {
                socket.connect(
                               new InetSocketAddress(PeerAddress.LOOPBACK_HOST, peerAcceptor.boundPort()),
                               2_000);
                socket.setTcpNoDelay(true);
                try (RingConnection localToPeer =
                        net.nexus_flow.core.ring.transport.TestRingConnections.over(
                                                                                    socket, PeerAddress.loopback(peerAcceptor
                                                                                            .boundPort()))) {
                    writerExec.submit(localToPeer::runWriteLoop);
                    localToPeer.bindPeerId(REMOTE);
                    localToPeer.markActive();
                    Map<PeerId, PeerAddress> seeds = new LinkedHashMap<>();
                    seeds.put(REMOTE, PeerAddress.loopback(peerAcceptor.boundPort()));
                    StaticPeerListMembership membership = new StaticPeerListMembership(T0, seeds);
                    membership.start();
                    membership.mutableRegistry().transition(REMOTE, PeerState.ALIVE);

                    RingConnectionRegistry connections = new RingConnectionRegistry();
                    connections.register(REMOTE, localToPeer);
                    PeerCursorTracker     cursors = new PeerCursorTracker();
                    InMemoryOutboxStorage outbox  = new InMemoryOutboxStorage(T0);

                    try (RingOutboxBridge bridge = new RingOutboxBridge(
                            LOCAL, outbox, connections, membership.registry(), cursors, T0,
                            Duration.ofMillis(100), 32, OutboxOwnership.RING_BRIDGE_ONLY)) {

                        TickAgg agg = new TickAgg();
                        agg.tick();
                        OutboxAppender.appendDrainedEvents(
                                                           agg.drainEvents(), ExecutionContext.root(),
                                                           outbox, T0, new JavaSerializationOutboxPayloadCodec());
                        assertEquals(1, outbox.size());

                        int processed = bridge.drainOnce();
                        assertEquals(1, processed);
                        assertEquals(0L, cursors.cursor(REMOTE),
                                     "first event has seq=0 on AbstractDomainEvent");
                        outbox.snapshot().forEach(
                                                  r -> assertEquals(OutboxStatus.PUBLISHED, r.status()));
                        await().atMost(2, TimeUnit.SECONDS)
                                .until(() -> localToPeer.outboundQueueDepth() == 0);
                    }
                }
            }
        }
    }

    @Test
    void drainOnce_skipsLocalSelfPeer() throws IOException {
        Map<PeerId, PeerAddress> seeds = new LinkedHashMap<>();
        seeds.put(LOCAL, PeerAddress.loopback(9999));
        StaticPeerListMembership membership = new StaticPeerListMembership(T0, seeds);
        membership.start();
        membership.mutableRegistry().transition(LOCAL, PeerState.ALIVE);

        InMemoryOutboxStorage outbox = new InMemoryOutboxStorage(T0);
        TickAgg               agg    = new TickAgg();
        agg.tick();
        OutboxAppender.appendDrainedEvents(
                                           agg.drainEvents(), ExecutionContext.root(),
                                           outbox, T0, new JavaSerializationOutboxPayloadCodec());

        PeerCursorTracker cursors = new PeerCursorTracker();
        try (RingOutboxBridge bridge = new RingOutboxBridge(
                LOCAL, outbox, new RingConnectionRegistry(), membership.registry(), cursors, T0,
                Duration.ofMillis(100), 32, OutboxOwnership.RING_BRIDGE_ONLY)) {
            bridge.drainOnce();
            assertEquals(0L, cursors.cursor(LOCAL));
            // Lonely-pod path: no alive non-self peers — row is PUBLISHED.
            outbox.snapshot().forEach(r -> assertEquals(OutboxStatus.PUBLISHED, r.status()));
        }
    }

    @Test
    void drainOnce_emptyOutbox_returnsZero() {
        StaticPeerListMembership membership = new StaticPeerListMembership(
                T0, Map.of(REMOTE, PeerAddress.loopback(9999)));
        membership.start();
        try (RingOutboxBridge bridge = new RingOutboxBridge(
                LOCAL, new InMemoryOutboxStorage(T0), new RingConnectionRegistry(),
                membership.registry(), new PeerCursorTracker(), T0,
                Duration.ofMillis(100), 32, OutboxOwnership.RING_BRIDGE_ONLY)) {
            assertEquals(0, bridge.drainOnce());
        }
    }

    @Test
    void drainOnce_skipsPeersNotAlive() throws IOException {
        Map<PeerId, PeerAddress> seeds = new LinkedHashMap<>();
        seeds.put(REMOTE, PeerAddress.loopback(9999));
        StaticPeerListMembership membership = new StaticPeerListMembership(T0, seeds);
        membership.start();

        InMemoryOutboxStorage outbox = new InMemoryOutboxStorage(T0);
        TickAgg               agg    = new TickAgg();
        agg.tick();
        OutboxAppender.appendDrainedEvents(
                                           agg.drainEvents(), ExecutionContext.root(),
                                           outbox, T0, new JavaSerializationOutboxPayloadCodec());

        PeerCursorTracker cursors = new PeerCursorTracker();
        try (RingOutboxBridge bridge = new RingOutboxBridge(
                LOCAL, outbox, new RingConnectionRegistry(), membership.registry(), cursors, T0,
                Duration.ofMillis(100), 32, OutboxOwnership.RING_BRIDGE_ONLY)) {
            bridge.drainOnce();
            assertEquals(0L, cursors.cursor(REMOTE), "non-ALIVE peer must not receive the event");
            // No live peers — lonely-pod path marks the row PUBLISHED.
            outbox.snapshot().forEach(r -> assertEquals(OutboxStatus.PUBLISHED, r.status()));
        }
    }

    @Test
    void replayTo_emptyOutbox_returnsZero() {
        StaticPeerListMembership membership = new StaticPeerListMembership(
                T0, Map.of(REMOTE, PeerAddress.loopback(9999)));
        membership.start();
        try (RingOutboxBridge bridge = new RingOutboxBridge(
                LOCAL, new InMemoryOutboxStorage(T0), new RingConnectionRegistry(),
                membership.registry(), new PeerCursorTracker(), T0,
                Duration.ofMillis(100), 32, OutboxOwnership.RING_BRIDGE_ONLY)) {
            assertEquals(0, bridge.replayTo(REMOTE, 0L));
        }
    }

    @Test
    void cursorSnapshot_isIndependentOfBridge() {
        StaticPeerListMembership membership = new StaticPeerListMembership(
                T0, Map.of(REMOTE, PeerAddress.loopback(9999)));
        membership.start();
        PeerCursorTracker cursors = new PeerCursorTracker();
        cursors.advance(REMOTE, 5L);
        try (RingOutboxBridge bridge = new RingOutboxBridge(
                LOCAL, new InMemoryOutboxStorage(T0), new RingConnectionRegistry(),
                membership.registry(), cursors, T0,
                Duration.ofMillis(100), 32, OutboxOwnership.RING_BRIDGE_ONLY)) {
            var snap = bridge.cursorSnapshot();
            assertEquals(1, snap.size());
            assertTrue(snap.containsKey(REMOTE));
            assertEquals(5L, snap.get(REMOTE));
        }
    }

    @Test
    void start_refusesWhenOwnershipIsLocalWorkerOnly() {
        StaticPeerListMembership membership = new StaticPeerListMembership(
                T0, Map.of(REMOTE, PeerAddress.loopback(9999)));
        membership.start();
        try (RingOutboxBridge bridge = new RingOutboxBridge(
                LOCAL, new InMemoryOutboxStorage(T0), new RingConnectionRegistry(),
                membership.registry(), new PeerCursorTracker(), T0,
                Duration.ofMillis(100), 32, OutboxOwnership.LOCAL_WORKER_ONLY)) {
            assertThrows(IllegalStateException.class, bridge::start);
        }
    }

    @Test
    void replayTo_usesNonDestructiveScan_doesNotAffectClaimBatch() throws Exception {
        Map<PeerId, PeerAddress> seeds = new LinkedHashMap<>();
        seeds.put(REMOTE, PeerAddress.loopback(9999));
        StaticPeerListMembership membership = new StaticPeerListMembership(T0, seeds);
        membership.start();
        membership.mutableRegistry().transition(REMOTE, PeerState.ALIVE);

        InMemoryOutboxStorage outbox = new InMemoryOutboxStorage(T0);
        TickAgg               agg    = new TickAgg();
        agg.tick();
        agg.tick();
        OutboxAppender.appendDrainedEvents(
                                           agg.drainEvents(), ExecutionContext.root(),
                                           outbox, T0, new JavaSerializationOutboxPayloadCodec());
        assertEquals(2, outbox.size());

        // Replay with no live connection — the cursor should not advance and rows should
        // remain in PENDING (the scan is non-destructive; no claim mutation).
        try (RingOutboxBridge bridge = new RingOutboxBridge(
                LOCAL, outbox, new RingConnectionRegistry(), membership.registry(),
                new PeerCursorTracker(), T0,
                Duration.ofMillis(100), 32, OutboxOwnership.RING_BRIDGE_ONLY)) {
            int replayed = bridge.replayTo(REMOTE, -1L);
            assertEquals(0, replayed);
            // Rows are still PENDING — non-destructive scan did not flip them.
            outbox.snapshot().forEach(r -> assertEquals(OutboxStatus.PENDING, r.status()));
        }
    }
}

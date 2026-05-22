package net.nexus_flow.core.ring.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxConfig;
import net.nexus_flow.core.outbox.OutboxWorker;
import net.nexus_flow.core.ring.membership.PeerState;
import net.nexus_flow.core.ring.membership.StaticPeerListMembership;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code RING_BRIDGE_WITH_WORKER_FAILOVER} handshake. The bridge MUST pause the
 * attached worker at {@code start()} and resume it at {@code close()}, so at-least-once is
 * preserved across the bridge's lifetime.
 */
class RingOutboxBridgeFailoverHandshakeTest {

    private static final PeerId LOCAL  = PeerId.of("local");
    private static final PeerId REMOTE = PeerId.of("remote");

    @Test
    void attach_thenStart_pausesWorker_andResumeOnClose() throws Exception {
        InMemoryOutboxStorage    outbox = new InMemoryOutboxStorage();
        StaticPeerListMembership m      = new StaticPeerListMembership(Clock.systemUTC(),
                Map.of(REMOTE, PeerAddress.loopback(9001)));
        m.start();
        m.mutableRegistry().transition(REMOTE, PeerState.ALIVE);

        try (FlowRuntime rt = FlowRuntime.builder().build()) {
            EventBus     bus = rt.events();
            OutboxConfig cfg = OutboxConfig.builder(outbox, new JavaSerializationOutboxPayloadCodec())
                    .workerPollInterval(Duration.ofMillis(50))
                    .autoStartWorker(false)
                    .build();
            try (OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast())) {
                worker.start();
                assertTrue(!worker.isPaused(),
                           "worker MUST be active before the bridge attaches");
                try (RingOutboxBridge bridge = new RingOutboxBridge(
                        LOCAL, outbox, new RingConnectionRegistry(), m.registry(),
                        new PeerCursorTracker(), Clock.systemUTC(),
                        Duration.ofMillis(100), 8,
                        OutboxOwnership.RING_BRIDGE_WITH_WORKER_FAILOVER)) {
                    bridge.attachOutboxWorker(worker);
                    bridge.start();
                    assertTrue(worker.isPaused(),
                               "bridge.start() MUST pause the attached worker — failover handshake");
                }
                assertTrue(!worker.isPaused(),
                           "bridge.close() MUST resume the worker so it can take over publishing");
            }
        }
    }

    @Test
    void attach_afterStart_isRejected_byContract() {
        InMemoryOutboxStorage    outbox = new InMemoryOutboxStorage();
        StaticPeerListMembership m      = new StaticPeerListMembership(Clock.systemUTC(),
                Map.of(REMOTE, PeerAddress.loopback(9001)));
        m.start();
        try (FlowRuntime rt = FlowRuntime.builder().build()) {
            EventBus     bus = rt.events();
            OutboxConfig cfg = OutboxConfig.builder(outbox, new JavaSerializationOutboxPayloadCodec())
                    .workerPollInterval(Duration.ofMillis(50))
                    .autoStartWorker(false)
                    .build();
            try (OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());
                    RingOutboxBridge bridge = new RingOutboxBridge(
                            LOCAL, outbox, new RingConnectionRegistry(), m.registry(),
                            new PeerCursorTracker(), Clock.systemUTC(),
                            Duration.ofMillis(100), 8,
                            OutboxOwnership.RING_BRIDGE_WITH_WORKER_FAILOVER)) {
                bridge.start();
                IllegalStateException ex = assertThrows(IllegalStateException.class,
                                                        () -> bridge.attachOutboxWorker(worker));
                assertTrue(ex.getMessage().contains("before start"),
                           "rejection MUST name the contract: " + ex.getMessage());
            }
        }
    }

    @Test
    void attach_onWrongOwnership_isRejected() {
        InMemoryOutboxStorage    outbox = new InMemoryOutboxStorage();
        StaticPeerListMembership m      = new StaticPeerListMembership(Clock.systemUTC(),
                Map.of(REMOTE, PeerAddress.loopback(9001)));
        m.start();
        try (FlowRuntime rt = FlowRuntime.builder().build()) {
            OutboxConfig cfg = OutboxConfig.builder(outbox, new JavaSerializationOutboxPayloadCodec())
                    .workerPollInterval(Duration.ofMillis(50))
                    .autoStartWorker(false)
                    .build();
            try (OutboxWorker worker = new OutboxWorker(cfg, rt.events(), ErrorPolicy.failFast());
                    RingOutboxBridge bridgeOnly = new RingOutboxBridge(
                            LOCAL, outbox, new RingConnectionRegistry(), m.registry(),
                            new PeerCursorTracker(), Clock.systemUTC(),
                            Duration.ofMillis(100), 8,
                            OutboxOwnership.RING_BRIDGE_ONLY)) {
                IllegalStateException ex = assertThrows(IllegalStateException.class,
                                                        () -> bridgeOnly.attachOutboxWorker(worker));
                assertEquals(true,
                             ex.getMessage().contains("RING_BRIDGE_WITH_WORKER_FAILOVER"),
                             "rejection MUST cite the required ownership mode: " + ex.getMessage());
            }
        }
    }
}

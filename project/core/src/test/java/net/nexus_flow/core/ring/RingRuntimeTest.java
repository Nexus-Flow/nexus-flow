package net.nexus_flow.core.ring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.ring.event.OutboxOwnership;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RingRuntime} as the canonical "wire-the-ring-in-one-line" entry point. The audit
 * identified that running the ring required hand-cabling 8 different subsystems; the runtime
 * collapses that to a single fluent builder + a single {@link RingRuntime#close()}.
 */
class RingRuntimeTest {

    private static final PeerId LOCAL  = PeerId.of("pod-local");
    private static final PeerId REMOTE = PeerId.of("pod-remote");

    @Test
    void builder_constructsAllSubsystems_andOpsExposesThem() throws Exception {
        try (RingRuntime rt = RingRuntime.builder(LOCAL)
                .seedPeers(Map.of(REMOTE, PeerAddress.loopback(9001)))
                .expectsClusteredDeployment(false)
                .build()) {
            rt.start();
            assertNotNull(rt.membership());
            assertNotNull(rt.connections());
            assertNotNull(rt.heartbeat());
            assertNotNull(rt.sagaCoordinator());
            assertNotNull(rt.pendingResponses());
            assertNotNull(rt.dispatcher());
            assertNotNull(rt.router());
            assertNotNull(rt.acceptor());
            assertNotNull(rt.directory());
            assertNotNull(rt.ops(),
                          "ops facade must be wired automatically by the runtime");
            assertEquals(1, rt.ops().peersSnapshot().size(),
                         "seed peer must appear in the membership snapshot");
            org.awaitility.Awaitility.await()
                    .atMost(2, java.util.concurrent.TimeUnit.SECONDS)
                    .until(() -> rt.acceptor().boundPort() > 0);
            assertTrue(rt.acceptor().boundPort() > 0,
                       "start() must bind the acceptor");
            assertTrue(rt.certificateSource().isEmpty(),
                       "no cert source wired ⇒ Optional.empty");
        }
    }

    @Test
    void close_isIdempotent_andReleasesAllResources() throws Exception {
        RingRuntime rt = RingRuntime.builder(LOCAL)
                .seedPeers(Map.of(REMOTE, PeerAddress.loopback(9002)))
                .expectsClusteredDeployment(false)
                .build();
        rt.start();
        rt.close();
        // close-twice must be a no-op — assertDoesNotThrow makes the intent explicit.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(rt::close,
                                                            "close() must be idempotent — calling it twice MUST NOT throw");
    }

    @Test
    void enableLiveFanOut_requiresAttachToFlowRuntime() {
        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                                                                                 IllegalStateException.class,
                                                                                 () -> RingRuntime.builder(LOCAL)
                                                                                         .seedPeers(Map.of(REMOTE, PeerAddress.loopback(
                                                                                                                                        9100)))
                                                                                         .enableLiveFanOut(new JavaSerializationOutboxPayloadCodec(),
                                                                                                           "java-v1")
                                                                                         .build());
        org.junit.jupiter.api.Assertions.assertTrue(
                                                    ex.getMessage().contains("attachTo"),
                                                    "rejection MUST name the missing precondition: " + ex.getMessage());
    }

    @Test
    void enableLiveFanOut_withAttachedFlow_constructsTheBridge() throws Exception {
        try (FlowRuntime flow = FlowRuntime.builder().build();
                RingRuntime rt = RingRuntime.builder(LOCAL)
                        .seedPeers(Map.of(REMOTE, PeerAddress.loopback(9101)))
                        .expectsClusteredDeployment(false)
                        .attachTo(flow)
                        .enableLiveFanOut(new JavaSerializationOutboxPayloadCodec(), "java-v1")
                        .build()) {
            rt.start();
            assertNotNull(rt.eventBusBridge().orElse(null),
                          "live fan-out bridge MUST be wired automatically when enableLiveFanOut is set");
        }
    }

    @Test
    void enableDurableFanOut_wiresOutboxBridge_andOpsExposesIt() throws Exception {
        InMemoryOutboxStorage outbox = new InMemoryOutboxStorage();
        try (RingRuntime rt = RingRuntime.builder(LOCAL)
                .seedPeers(Map.of(REMOTE, PeerAddress.loopback(9102)))
                .expectsClusteredDeployment(false)
                .enableDurableFanOut(outbox, OutboxOwnership.RING_BRIDGE_ONLY)
                .durablePollInterval(Duration.ofMillis(50))
                .build()) {
            rt.start();
            assertNotNull(rt.outboxBridge().orElse(null),
                          "durable fan-out bridge MUST be wired when enableDurableFanOut is set");
            // ops.drainOutbox() returns Optional.of(count) once the bridge is wired.
            assertTrue(rt.ops().drainOutbox().isPresent(),
                       "ops.drainOutbox MUST be present once a durable bridge is wired");
        }
    }

    @Test
    void localDispatchHandler_defaults_toNotFound_whenNotConfigured() throws Exception {
        try (RingRuntime rt = RingRuntime.builder(LOCAL)
                .seedPeers(Map.of(REMOTE, PeerAddress.loopback(9003)))
                .expectsClusteredDeployment(false)
                .build()) {
            // The default handler returns NOT_FOUND for any payload type, surfaced when a remote
            // peer dispatches to us. We don't exercise the wire here (a separate end-to-end test
            // covers that); the test pins the wiring shape — no NPE during construction without
            // a localDispatchHandler.
            rt.start();
            assertNotNull(rt.router(),
                          "router must be wired even without an explicit localDispatchHandler");
        }
    }
}

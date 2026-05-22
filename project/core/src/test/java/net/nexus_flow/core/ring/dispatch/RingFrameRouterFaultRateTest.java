package net.nexus_flow.core.ring.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.ring.membership.DefaultMembershipRegistry;
import net.nexus_flow.core.ring.membership.HeartbeatConfig;
import net.nexus_flow.core.ring.membership.HeartbeatFailureDetector;
import net.nexus_flow.core.ring.membership.StaticPeerListMembership;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.registry.DefaultHandlerDirectory;
import net.nexus_flow.core.ring.registry.RoundRobinPeerSelector;
import net.nexus_flow.core.ring.saga.LeaseClaimOutcome;
import net.nexus_flow.core.ring.saga.LeaseRegistry;
import net.nexus_flow.core.ring.saga.SagaLease;
import net.nexus_flow.core.ring.saga.SagaLeaseCoordinator;
import net.nexus_flow.core.ring.saga.SagaLeaseCoordinatorConfig;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.transport.TestRingConnections;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.RingFrame;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-peer fault-rate threshold. A peer that crosses the {@link RouterFaultLimits}
 * budget MUST have its connection closed by the router; until the budget is exhausted the
 * connection stays open. The Javadoc on {@link RingFrameRouter} documents this behaviour as
 * part of the router contract.
 */
class RingFrameRouterFaultRateTest {

    private static final PeerId LOCAL  = PeerId.of("local");
    private static final PeerId REMOTE = PeerId.of("remote");

    /** Movable clock so window-expiry is exercised deterministically. */
    private static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public long millis() {
            return now.toEpochMilli();
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private record Fixture(
                           RingFrameRouter router,
                           HeartbeatFailureDetector heartbeat,
                           SagaLeaseCoordinator sagaCoord,
                           ScheduledExecutorService scheduler,
                           MovableClock clock) implements AutoCloseable {
        @Override
        public void close() {
            heartbeat.close();
            sagaCoord.close();
            scheduler.shutdownNow();
        }
    }

    private static Fixture build(RouterFaultLimits limits, RingFrameRouter.RingEventInboundHandler inbound) {
        MovableClock             clock = new MovableClock(Instant.parse("2026-05-27T12:00:00Z"));
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        Map<PeerId, PeerAddress> seeds = new LinkedHashMap<>();
        seeds.put(REMOTE, PeerAddress.loopback(9001));
        StaticPeerListMembership m = new StaticPeerListMembership(clock, seeds);
        m.start();
        RingConnectionRegistry    connections   = new RingConnectionRegistry();
        DefaultMembershipRegistry mutable       = m.mutableRegistry();
        HeartbeatFailureDetector  heartbeat     = new HeartbeatFailureDetector(
                HeartbeatConfig.defaults(LOCAL), clock, connections, mutable);
        LeaseRegistry             leaseRegistry = new LeaseRegistry(clock);
        SagaLeaseCoordinator      sagaCoord     = new SagaLeaseCoordinator(
                SagaLeaseCoordinatorConfig.defaults(LOCAL),
                clock,
                leaseRegistry,
                connections,
                (sagaId, owner, expiry) -> new LeaseClaimOutcome.Claimed(
                        new SagaLease(sagaId, owner, expiry)),
                m.registry());
        PendingResponseRegistry   pending       = new PendingResponseRegistry(16, sched);
        DefaultHandlerDirectory   directory     = new DefaultHandlerDirectory();
        RingDispatcher            dispatcher    = new RingDispatcher(
                LOCAL, connections, directory, new RoundRobinPeerSelector(), pending);
        LocalDispatchHandler      local         = ctx -> DispatchResponseEnvelope.success(
                                                                                          ctx.request().correlationId(), "", "",
                                                                                          new byte[0]);
        RingFrameRouter           router        = new RingFrameRouter(
                heartbeat, sagaCoord, dispatcher, local,
                DispatchAuthorizer.ALLOW_ALL, RingMetrics.noOp(),
                clock, inbound, limits);
        return new Fixture(router, heartbeat, sagaCoord, sched, clock);
    }

    private static RingConnection boundStub() throws IOException {
        RingConnection conn = TestRingConnections.stub();
        conn.bindPeerId(REMOTE);
        return conn;
    }

    @Test
    void faultsUnderBudget_doNotCloseConnection() throws Exception {
        RouterFaultLimits                       limits  = new RouterFaultLimits(3, Duration.ofSeconds(60));
        AtomicReference<RuntimeException>       thrown  = new AtomicReference<>();
        RingFrameRouter.RingEventInboundHandler inbound = (sender, frame) -> {
                                                            RuntimeException e = new RuntimeException("simulated handler fault");
                                                            thrown.set(e);
                                                            throw e;
                                                        };
        try (Fixture f = build(limits, inbound)) {
            RingConnection conn = boundStub();
            // Feed faults up to the budget — connection stays open.
            for (int i = 0; i < 3; i++) {
                f.router.onFrame(conn, new RingFrame(FrameType.EVENT, new byte[]{0}));
            }
            assertFalse(conn.isClosed(),
                        "connection MUST stay open for faults within budget");
            assertEquals(3, f.router.faultCount(REMOTE));
        }
    }

    @Test
    void exceedingBudget_closesConnection() throws Exception {
        RouterFaultLimits                       limits  = new RouterFaultLimits(3, Duration.ofSeconds(60));
        RingFrameRouter.RingEventInboundHandler inbound = (sender, frame) -> {
                                                            throw new RuntimeException("simulated handler fault");
                                                        };
        try (Fixture f = build(limits, inbound)) {
            RingConnection conn = boundStub();
            for (int i = 0; i < 3; i++) {
                f.router.onFrame(conn, new RingFrame(FrameType.EVENT, new byte[]{0}));
            }
            assertFalse(conn.isClosed());
            // The 4th fault crosses the budget — connection MUST close.
            f.router.onFrame(conn, new RingFrame(FrameType.EVENT, new byte[]{0}));
            assertTrue(conn.isClosed(),
                       "connection MUST close once the per-peer fault budget is exceeded");
        }
    }

    @Test
    void windowExpiry_resetsBudget() throws Exception {
        RouterFaultLimits                       limits  = new RouterFaultLimits(2, Duration.ofSeconds(60));
        RingFrameRouter.RingEventInboundHandler inbound = (sender, frame) -> {
                                                            throw new RuntimeException("simulated handler fault");
                                                        };
        try (Fixture f = build(limits, inbound)) {
            RingConnection conn = boundStub();
            // Two faults — at budget but not over.
            f.router.onFrame(conn, new RingFrame(FrameType.EVENT, new byte[]{0}));
            f.router.onFrame(conn, new RingFrame(FrameType.EVENT, new byte[]{0}));
            assertFalse(conn.isClosed());
            assertEquals(2, f.router.faultCount(REMOTE));
            // Advance past the window — old faults must roll off the rolling window.
            f.clock.advance(Duration.ofSeconds(61));
            assertEquals(0, f.router.faultCount(REMOTE),
                         "rolling window MUST drop entries beyond the configured duration");
            // Two MORE faults: still under budget.
            f.router.onFrame(conn, new RingFrame(FrameType.EVENT, new byte[]{0}));
            f.router.onFrame(conn, new RingFrame(FrameType.EVENT, new byte[]{0}));
            assertFalse(conn.isClosed(),
                        "faults in a new window must not accumulate with the expired ones");
        }
    }

    @Test
    void protocolViolation_doesNotCountTowardBudget() throws Exception {
        // RingProtocolException already causes the read loop to close — counting it again
        // toward the per-peer budget would be double-counting. The handler MUST exclude it.
        RouterFaultLimits                       limits  = new RouterFaultLimits(3, Duration.ofSeconds(60));
        RingFrameRouter.RingEventInboundHandler inbound = (sender, frame) -> {
                                                            throw new net.nexus_flow.core.ring.wire.RingProtocolException("malformed body");
                                                        };
        try (Fixture f = build(limits, inbound)) {
            RingConnection conn = boundStub();
            // The protocol exception escapes to the read loop — we catch and rethrow in the
            // test to assert it. The fault counter for this peer MUST remain 0.
            try {
                f.router.onFrame(conn, new RingFrame(FrameType.EVENT, new byte[]{0}));
            } catch (net.nexus_flow.core.ring.wire.RingProtocolException expected) {
                // expected
            }
            assertEquals(0, f.router.faultCount(REMOTE),
                         "RingProtocolException is handled by immediate close; MUST NOT count");
        }
    }

    @Test
    void onClosed_clearsFaultWindow_forRejoinerToStartFresh() throws Exception {
        RouterFaultLimits                       limits  = new RouterFaultLimits(3, Duration.ofSeconds(60));
        RingFrameRouter.RingEventInboundHandler inbound = (sender, frame) -> {
                                                            throw new RuntimeException("simulated handler fault");
                                                        };
        try (Fixture f = build(limits, inbound)) {
            RingConnection conn = boundStub();
            for (int i = 0; i < 2; i++) {
                f.router.onFrame(conn, new RingFrame(FrameType.EVENT, new byte[]{0}));
            }
            assertEquals(2, f.router.faultCount(REMOTE));
            f.router.onClosed(conn, null);
            assertEquals(0, f.router.faultCount(REMOTE),
                         "onClosed MUST clear the per-peer fault window so a rejoiner is fresh");
        }
    }
}

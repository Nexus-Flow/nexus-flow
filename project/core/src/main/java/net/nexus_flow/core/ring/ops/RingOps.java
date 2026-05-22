package net.nexus_flow.core.ring.ops;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import net.nexus_flow.core.ring.event.RingOutboxBridge;
import net.nexus_flow.core.ring.membership.MembershipRegistry;
import net.nexus_flow.core.ring.membership.PeerInfo;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingAcceptor;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Unified operations facade for the ring. The audit identified that running the ring in
 * production required reaching into 5+ different objects to answer simple operator questions
 * ("how many peers are alive?", "how many in-flight dispatches?", "drain the outbox now"). This
 * facade centralizes those calls behind a single SPI seat that adapter modules (k8s probes, JMX,
 * an HTTP admin server) can expose without re-learning the internal layout each time.
 *
 * <h2>What it does NOT do</h2>
 *
 * <ul>
 * <li>It does NOT own lifecycle of any subsystem — pass references to already-constructed
 * pieces. The ring's own {@code RingRuntime} (coordinator class) owns the closes; this
 * facade is a read-mostly view + a few "kick" operations (drain, quiesce).
 * <li>It does NOT cache the snapshots — every call reflects live state. Operators get
 * consistent reads only within a single facade call.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * The facade is thread-safe: every delegate it forwards to is already concurrent
 * (MembershipRegistry, RingConnectionRegistry, RingAcceptor, RingOutboxBridge). The facade
 * adds no internal state, only references.
 */
public final class RingOps {

    private final RingHealthChecker          health;
    private final MembershipRegistry         membership;
    private final RingConnectionRegistry     connections;
    private final RingAcceptor               acceptor;
    private final @Nullable RingOutboxBridge outboxBridge;
    private final IntSupplier                pendingDispatches;

    private RingOps(Builder b) {
        this.health            = Objects.requireNonNull(b.health, "health");
        this.membership        = Objects.requireNonNull(b.membership, "membership");
        this.connections       = Objects.requireNonNull(b.connections, "connections");
        this.acceptor          = Objects.requireNonNull(b.acceptor, "acceptor");
        this.outboxBridge      = b.outboxBridge;
        this.pendingDispatches = Objects.requireNonNull(b.pendingDispatches, "pendingDispatches");
    }

    /** Snapshot health status — composite UP/DEGRADED/DOWN with diagnostics. */
    public RingHealthStatus health() {
        return health.check();
    }

    /**
     * Live membership snapshot. Returns every peer the registry currently knows about, in any
     * state (ALIVE, SUSPECT, CONFIRMED_DEAD, LEFT, JOINING). Operators filter by state.
     */
    public Collection<PeerInfo> peersSnapshot() {
        return membership.peers();
    }

    /** Live connection peer IDs (every peer the local pod currently has a TCP / TLS conn to). */
    public Collection<PeerId> connectedPeers() {
        return connections.peerIds();
    }

    /** Number of inbound connections the acceptor currently has open. */
    public int acceptorLiveConnections() {
        return acceptor.liveConnections();
    }

    /** Number of in-flight cross-pod dispatches waiting for a response. */
    public int pendingDispatchCount() {
        return pendingDispatches.getAsInt();
    }

    /**
     * Force the outbox bridge to drain its pending rows on the caller thread, returning the
     * number of rows processed. Useful for pre-shutdown drains and Kubernetes pre-stop hooks.
     * Returns {@link Optional#empty()} when no outbox bridge is wired (live-only deployment).
     */
    public Optional<Integer> drainOutbox() {
        if (outboxBridge == null) {
            return Optional.empty();
        }
        return Optional.of(outboxBridge.drainOnce());
    }

    /**
     * Block until the ring is quiet — every in-flight dispatch has resolved AND the outbox is
     * empty — or until {@code timeout} elapses. Returns {@code true} when quiescence was
     * achieved within the deadline. Intended for the pre-shutdown sequence in
     * {@code RingRuntime.shutdown(grace)}.
     *
     * <p>This is a polling implementation (50ms ticks) — for the volumes a single pod handles
     * in normal operation that is fine; if a deployment ends up hammering this in a hot loop
     * it can be replaced with a push-notification primitive without changing the contract.
     */
    public boolean quiesce(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadlineNanos = System.nanoTime() + Math.max(0L, timeout.toNanos());
        long sleepNanos    = Duration.ofMillis(50).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            int pending  = pendingDispatches.getAsInt();
            int drainNow = outboxBridge == null ? 0 : outboxBridge.drainOnce();
            if (pending == 0 && drainNow == 0) {
                return true;
            }
            try {
                java.util.concurrent.locks.LockSupport.parkNanos(sleepNanos);
            } catch (RuntimeException ignored) {
                // park interruption — fall through to deadline re-check
            }
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
        }
        return pendingDispatches.getAsInt() == 0 && (outboxBridge == null || outboxBridge.drainOnce() == 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder. Every reference is mandatory except {@code outboxBridge}. */
    public static final class Builder {
        private @Nullable RingHealthChecker      health;
        private @Nullable MembershipRegistry     membership;
        private @Nullable RingConnectionRegistry connections;
        private @Nullable RingAcceptor           acceptor;
        private @Nullable RingOutboxBridge       outboxBridge;
        private @Nullable IntSupplier            pendingDispatches;

        public Builder health(RingHealthChecker health) {
            this.health = Objects.requireNonNull(health, "health");
            return this;
        }

        public Builder membership(MembershipRegistry membership) {
            this.membership = Objects.requireNonNull(membership, "membership");
            return this;
        }

        public Builder connections(RingConnectionRegistry connections) {
            this.connections = Objects.requireNonNull(connections, "connections");
            return this;
        }

        public Builder acceptor(RingAcceptor acceptor) {
            this.acceptor = Objects.requireNonNull(acceptor, "acceptor");
            return this;
        }

        public Builder outboxBridge(@Nullable RingOutboxBridge bridge) {
            this.outboxBridge = bridge;
            return this;
        }

        public Builder pendingDispatches(IntSupplier supplier) {
            this.pendingDispatches = Objects.requireNonNull(supplier, "pendingDispatches");
            return this;
        }

        public RingOps build() {
            return new RingOps(this);
        }
    }
}

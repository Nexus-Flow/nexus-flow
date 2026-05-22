package net.nexus_flow.core.ring.ops;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated health view of the local ring. Returned by {@link RingHealthChecker#check()};
 * suitable as the response body of a Kubernetes / load-balancer health endpoint.
 *
 * @param level                 overall classification — UP / DEGRADED / DOWN
 * @param checkedAt             wall-clock instant the check ran
 * @param livePeerCount         peers currently {@link
 *                              net.nexus_flow.core.ring.membership.PeerState#isRoutable() routable}
 * @param totalPeerCount        total peers in the membership view (including CONFIRMED_DEAD / LEFT)
 * @param activeConnectionCount live transport connections (accepted + dialed)
 * @param pendingDispatchCount  in-flight cross-pod dispatches awaiting a response
 * @param diagnostics           human-readable per-subsystem diagnostic lines, useful in dashboards
 */
public record RingHealthStatus(
                               Level level,
                               Instant checkedAt,
                               int livePeerCount,
                               int totalPeerCount,
                               int activeConnectionCount,
                               int pendingDispatchCount,
                               List<String> diagnostics) {

    /** Three-state aggregate level — same semantics as Spring Boot Actuator / K8s probes. */
    public enum Level {
        /** All subsystems healthy, ring is fully operational. */
        UP,

        /**
         * Some subsystems degraded but the local pod can still serve traffic. Examples: one peer
         * SUSPECT, pending-dispatch backlog above warning threshold, persisted state stale.
         * Suitable for readiness=ok but emit an alert for operator awareness.
         */
        DEGRADED,

        /**
         * Ring is non-functional for at least one critical subsystem. Examples: acceptor not
         * bound, zero live peers in a clustered deployment. Readiness probes should return
         * 503 so the load balancer drains traffic.
         */
        DOWN
    }

    public RingHealthStatus {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (livePeerCount < 0 || totalPeerCount < 0 || activeConnectionCount < 0 || pendingDispatchCount < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        if (livePeerCount > totalPeerCount) {
            throw new IllegalArgumentException(
                    "livePeerCount " + livePeerCount + " > totalPeerCount " + totalPeerCount);
        }
        Objects.requireNonNull(diagnostics, "diagnostics");
        diagnostics = List.copyOf(diagnostics);
    }

    /** Convenience: is the ring up enough to serve traffic? */
    public boolean isReady() {
        return level != Level.DOWN;
    }

    /** Convenience: is the ring fully healthy? */
    public boolean isFullyHealthy() {
        return level == Level.UP;
    }
}

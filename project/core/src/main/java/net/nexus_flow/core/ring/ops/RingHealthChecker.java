package net.nexus_flow.core.ring.ops;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import net.nexus_flow.core.ring.membership.MembershipRegistry;
import net.nexus_flow.core.ring.membership.PeerState;

/**
 * Aggregates the membership view, the transport's live-connection count, and the
 * dispatcher's pending-response count into a {@link RingHealthStatus}.
 *
 * <h2>Why a checker, not a callable on each subsystem</h2>
 *
 * The level discriminator ({@link RingHealthStatus.Level#UP UP} /
 * {@link RingHealthStatus.Level#DEGRADED DEGRADED} /
 * {@link RingHealthStatus.Level#DOWN DOWN}) is a single composite signal — three subsystems
 * agreeing on a level matters more than per-subsystem booleans. The checker owns that
 * composition so the policy lives in ONE place (a future operator preference for "DOWN
 * only if zero peers AND backlog over 1024" can be tuned here without changing every caller).
 */
public final class RingHealthChecker {

    private final Clock              clock;
    private final MembershipRegistry membership;
    private final IntSupplier        activeConnections;
    private final IntSupplier        pendingDispatches;
    private final int                pendingDispatchWarnThreshold;
    private final boolean            expectsClusteredDeployment;

    /**
     * @param clock                        for the {@code checkedAt} timestamp; must not be {@code null}
     * @param membership                   the membership view; must not be {@code null}
     * @param activeConnections            supplier of the live-connection count (typically
     *                                     {@code acceptor::liveConnections} + dialer total); must not be {@code null}
     * @param pendingDispatches            supplier of the in-flight cross-pod dispatch count (typically
     *                                     {@code pendingResponseRegistry::inFlight}); must not be {@code null}
     * @param pendingDispatchWarnThreshold backlog at or above this value flips the level to
     *                                     DEGRADED; must be {@code >= 1}
     * @param expectsClusteredDeployment   when {@code true}, zero live peers triggers DOWN;
     *                                     when {@code false} (single-pod / standalone), zero peers is normal and the checker
     *                                     reports UP regardless
     */
    public RingHealthChecker(
            Clock clock,
            MembershipRegistry membership,
            IntSupplier activeConnections,
            IntSupplier pendingDispatches,
            int pendingDispatchWarnThreshold,
            boolean expectsClusteredDeployment) {
        this.clock             = Objects.requireNonNull(clock, "clock");
        this.membership        = Objects.requireNonNull(membership, "membership");
        this.activeConnections = Objects.requireNonNull(activeConnections, "activeConnections");
        this.pendingDispatches = Objects.requireNonNull(pendingDispatches, "pendingDispatches");
        if (pendingDispatchWarnThreshold < 1) {
            throw new IllegalArgumentException(
                    "pendingDispatchWarnThreshold must be >= 1: " + pendingDispatchWarnThreshold);
        }
        this.pendingDispatchWarnThreshold = pendingDispatchWarnThreshold;
        this.expectsClusteredDeployment   = expectsClusteredDeployment;
    }

    /** Run a snapshot health check. Cheap — O(peers) over the membership snapshot. */
    public RingHealthStatus check() {
        var  peers   = membership.peers();
        int  total   = peers.size();
        long live    = peers.stream().filter(p -> p.state() == PeerState.ALIVE).count();
        long suspect = peers.stream().filter(p -> p.state() == PeerState.SUSPECT).count();
        int  active  = activeConnections.getAsInt();
        int  pending = pendingDispatches.getAsInt();

        List<String> diag = new ArrayList<>();
        if (suspect > 0) {
            diag.add(suspect + " peer(s) in SUSPECT state");
        }
        if (pending >= pendingDispatchWarnThreshold) {
            diag.add(
                     "pending dispatch backlog "
                             + pending
                             + " >= warn threshold "
                             + pendingDispatchWarnThreshold);
        }

        RingHealthStatus.Level level;
        if (expectsClusteredDeployment && live == 0) {
            level = RingHealthStatus.Level.DOWN;
            diag.add("zero peers ALIVE in a clustered deployment");
        } else if (suspect > 0 || pending >= pendingDispatchWarnThreshold) {
            level = RingHealthStatus.Level.DEGRADED;
        } else {
            level = RingHealthStatus.Level.UP;
        }

        return new RingHealthStatus(level, clock.instant(), (int) live, total, active, pending, diag);
    }
}

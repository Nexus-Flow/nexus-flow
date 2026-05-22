package net.nexus_flow.core.ring.saga;

import java.time.Duration;
import java.util.Objects;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Tuning knobs for {@link SagaLeaseCoordinator}.
 *
 * <h2>Time budget</h2>
 *
 * <ul>
 * <li>{@code leaseTtl} — how long each lease lives between renewals; default 10s.
 * <li>{@code renewalInterval} — how often this peer broadcasts SAGA_STATE for sagas it
 * owns; default {@code leaseTtl / 3} so two renewal attempts can fail without the
 * lease lapsing.
 * <li>{@code claimInterval} — how often the coordinator scans the {@link LeaseRegistry}
 * for expired leases; default 1s. The scan is also triggered immediately on
 * {@link net.nexus_flow.core.ring.membership.MembershipEvent.PeerLeft}.
 * </ul>
 *
 * Total worst-case ownership-handoff latency = {@code leaseTtl + claimInterval} (lease
 * expires, scan happens, claim CAS, broadcast). With defaults: ~11s.
 *
 * @param localPeerId     this pod's id (embedded in every renewal broadcast)
 * @param leaseTtl        how long each renewal grants the lease
 * @param renewalInterval how often own leases are broadcast for renewal
 * @param claimInterval   how often the expired-lease scan runs
 * @param shutdownGrace   bound on {@link SagaLeaseCoordinator#shutdown()} join
 */
public record SagaLeaseCoordinatorConfig(
                                         PeerId localPeerId,
                                         Duration leaseTtl,
                                         Duration renewalInterval,
                                         Duration claimInterval,
                                         Duration shutdownGrace) {

    public static final long DEFAULT_LEASE_TTL_MS = 10_000L;
    public static final long DEFAULT_CLAIM_INTERVAL_MS = 1_000L;
    public static final long DEFAULT_SHUTDOWN_GRACE_MS = 2_000L;

    public SagaLeaseCoordinatorConfig {
        Objects.requireNonNull(localPeerId, "localPeerId");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (leaseTtl.isNegative() || leaseTtl.isZero()) {
            throw new IllegalArgumentException("leaseTtl must be positive: " + leaseTtl);
        }
        Objects.requireNonNull(renewalInterval, "renewalInterval");
        if (renewalInterval.isNegative() || renewalInterval.isZero()) {
            throw new IllegalArgumentException(
                    "renewalInterval must be positive: " + renewalInterval);
        }
        if (renewalInterval.compareTo(leaseTtl) >= 0) {
            throw new IllegalArgumentException(
                    "renewalInterval ("
                            + renewalInterval
                            + ") must be strictly less than leaseTtl ("
                            + leaseTtl
                            + ") so two renewal attempts can fail before the lease lapses");
        }
        Objects.requireNonNull(claimInterval, "claimInterval");
        if (claimInterval.isNegative() || claimInterval.isZero()) {
            throw new IllegalArgumentException("claimInterval must be positive: " + claimInterval);
        }
        Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        if (shutdownGrace.isNegative() || shutdownGrace.isZero()) {
            throw new IllegalArgumentException("shutdownGrace must be positive: " + shutdownGrace);
        }
    }

    /** Production-default factory. */
    public static SagaLeaseCoordinatorConfig defaults(PeerId localPeerId) {
        Duration ttl = Duration.ofMillis(DEFAULT_LEASE_TTL_MS);
        return new SagaLeaseCoordinatorConfig(
                localPeerId,
                ttl,
                ttl.dividedBy(3),
                Duration.ofMillis(DEFAULT_CLAIM_INTERVAL_MS),
                Duration.ofMillis(DEFAULT_SHUTDOWN_GRACE_MS));
    }
}

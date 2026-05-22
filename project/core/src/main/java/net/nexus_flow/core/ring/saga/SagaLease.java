package net.nexus_flow.core.ring.saga;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.saga.SagaId;

/**
 * Immutable view of one saga's ownership lease — who owns it and when their lease expires.
 * Distinct from the persisted {@link net.nexus_flow.core.saga.SagaState} (which carries the
 * application-level state) — a lease describes ONLY the ownership claim and never carries
 * application data.
 *
 * @param sagaId      the saga the lease is for
 * @param ownerPeerId the peer that currently owns the saga
 * @param expiresAt   wall-clock instant at which the lease expires unless the owner renews; UTC
 */
public record SagaLease(SagaId sagaId, PeerId ownerPeerId, Instant expiresAt) {

    public SagaLease {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(ownerPeerId, "ownerPeerId");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * Factory for a freshly-acquired lease with TTL relative to {@code clock}'s current
     * instant.
     */
    public static SagaLease owned(SagaId sagaId, PeerId ownerPeerId, Clock clock, Duration ttl) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        return new SagaLease(sagaId, ownerPeerId, clock.instant().plus(ttl));
    }

    /** @return {@code true} if the lease has expired against {@code now} (exclusive). */
    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(expiresAt);
    }

    /** @return a fresh {@code SagaLease} with the same owner and a renewed expiry. */
    public SagaLease renewed(Instant newExpiresAt) {
        Objects.requireNonNull(newExpiresAt, "newExpiresAt");
        return new SagaLease(sagaId, ownerPeerId, newExpiresAt);
    }

    /** @return a fresh {@code SagaLease} with a different owner and a fresh expiry. */
    public SagaLease handedOffTo(PeerId newOwner, Instant newExpiresAt) {
        Objects.requireNonNull(newOwner, "newOwner");
        Objects.requireNonNull(newExpiresAt, "newExpiresAt");
        return new SagaLease(sagaId, newOwner, newExpiresAt);
    }
}

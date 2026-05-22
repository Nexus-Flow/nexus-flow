package net.nexus_flow.core.saga;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Authoritative ownership record for a saga instance — the persistence-side counterpart to
 * the ring's {@link net.nexus_flow.core.ring.saga.SagaLease}. While {@code SagaLease} is the
 * wire envelope used by ring peers to gossip lease intent, this type is the durable
 * source-of-truth: a {@link SagaStorage} compare-and-set on this record is what actually
 * enforces single-writer per saga across replicas.
 *
 * <h2>Why a separate type, not just a field on {@link SagaState}</h2>
 *
 * The state machine and the ownership lease have orthogonal lifecycles:
 *
 * <ul>
 * <li>{@link SagaState} mutates on every business-event handling and is versioned via
 * optimistic concurrency.
 * <li>Ownership changes happen at lease boundaries (acquisition, renewal, handoff) and
 * are typically far less frequent than state transitions.
 * </ul>
 *
 * Keeping them separate lets a JDBC backend index the ownership column independently of the
 * state blob and lets the framework compare-and-set ownership without touching the state
 * row.
 *
 * @param ownerPeerId    the peer that currently holds the lease; {@code null} for an
 *                       unowned saga (initial state)
 * @param leaseExpiresAt wall-clock expiry of the current lease; readers compare against
 *                       their own clock and treat {@code now > leaseExpiresAt} as expired
 * @param fencingToken   monotonically increasing token that distinguishes successive
 *                       ownership grants. Operators forwarding side effects through
 *                       external systems include this token so a "stale owner finishes
 *                       after handoff" cannot overwrite the new owner's work
 */
public record SagaOwnership(
                            @Nullable String ownerPeerId, @Nullable Instant leaseExpiresAt, long fencingToken) {

    /** Sentinel for an unclaimed saga. */
    public static final SagaOwnership UNOWNED = new SagaOwnership(null, null, 0L);

    public SagaOwnership {
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0: " + fencingToken);
        }
        if (ownerPeerId == null && leaseExpiresAt != null) {
            throw new IllegalArgumentException(
                    "leaseExpiresAt must be null when ownerPeerId is null");
        }
        if (ownerPeerId != null) {
            Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        }
    }

    /** {@code true} when this record names no current owner. */
    public boolean isUnowned() {
        return ownerPeerId == null;
    }

    /**
     * @return {@code true} when this is a non-{@link #UNOWNED} record whose lease has
     *         passed {@code now}
     */
    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return leaseExpiresAt != null && !now.isBefore(leaseExpiresAt);
    }
}

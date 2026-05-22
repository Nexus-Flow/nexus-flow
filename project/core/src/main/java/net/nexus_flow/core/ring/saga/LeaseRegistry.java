package net.nexus_flow.core.ring.saga;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.saga.SagaId;

/**
 * Per-peer view of which saga is owned by whom, updated from inbound SAGA_STATE / LEASE_GRANT
 * frames. The registry is the local cache that drives "should I attempt a lease claim for
 * this saga right now" decisions — the authoritative source is still
 * {@link net.nexus_flow.core.saga.SagaStorage}.
 *
 * <h2>Thread safety</h2>
 *
 * Backed by a {@link ConcurrentHashMap}. Reads are lock-free. Writes use {@code compute}
 * primitives so concurrent updates from different inbound frames cannot lose state.
 */
public final class LeaseRegistry {

    private final Clock                                clock;
    private final ConcurrentHashMap<SagaId, SagaLease> leases = new ConcurrentHashMap<>();

    /**
     * @param clock used by {@link #expiredLeases()} to decide which entries are past expiry;
     *              must not be {@code null}
     */
    public LeaseRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Record a lease observation. The newer expiry wins — out-of-order frames cannot
     * regress an observed lease. If {@code observed} has a strictly earlier expiry than the
     * current entry, the entry is left untouched.
     */
    public void observe(SagaLease observed) {
        Objects.requireNonNull(observed, "observed");
        leases.compute(
                       observed.sagaId(),
                       (k, existing) -> {
                           if (existing == null) {
                               return observed;
                           }
                           return existing.expiresAt().isBefore(observed.expiresAt()) ? observed : existing;
                       });
    }

    /** Drop the registry entry for {@code sagaId}, e.g. when the saga terminates cleanly. */
    public void forget(SagaId sagaId) {
        Objects.requireNonNull(sagaId, "sagaId");
        leases.remove(sagaId);
    }

    /** Current observed lease for {@code sagaId}, or {@link Optional#empty()} if unknown. */
    public Optional<SagaLease> lease(SagaId sagaId) {
        Objects.requireNonNull(sagaId, "sagaId");
        return Optional.ofNullable(leases.get(sagaId));
    }

    /** Returns every lease this peer currently believes is owned by {@code peerId}. */
    public List<SagaLease> ownedBy(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId");
        return leases.values().stream()
                .filter(lease -> lease.ownerPeerId().equals(peerId))
                .toList();
    }

    /**
     * Returns every lease whose {@code expiresAt} is at or before the registry's clock's
     * current instant — the candidates for a lease-claim attempt.
     */
    public List<SagaLease> expiredLeases() {
        Instant now = clock.instant();
        return leases.values().stream().filter(lease -> lease.isExpired(now)).toList();
    }

    /** Snapshot of every known lease. */
    public Collection<SagaLease> snapshot() {
        return List.copyOf(leases.values());
    }

    /** Current count of tracked leases. */
    public int size() {
        return leases.size();
    }
}

package net.nexus_flow.core.ring.saga;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.saga.SagaId;

/**
 * Per-peer view of which saga is owned by whom, updated from inbound SAGA_STATE / LEASE_GRANT
 * frames. The registry is the local cache that drives "should I attempt a lease claim for
 * this saga right now" decisions — the authoritative source is still
 * {@link net.nexus_flow.core.saga.SagaStorage}.
 *
 * <h2>Indices</h2>
 *
 * Three coherent indices, kept in lockstep through {@link #observe(SagaLease)} and
 * {@link #forget(SagaId)}:
 *
 * <ul>
 * <li>{@code leaseIndex} — primary by {@link SagaId}, lock-free {@link ConcurrentHashMap}
 * so {@link #lease(SagaId)} is O(1).
 * <li>{@code sagaIdsByPeerIndex} — secondary by {@link PeerId}, lets
 * {@link #ownedBy(PeerId)} return rows in O(K-for-peer) instead of scanning every
 * lease.
 * <li>{@code leasesByDeadlineIndex} — secondary by {@code (expiresAt, sagaId)}, lets
 * {@link #expiredLeases()} head-walk in O(K-expired) instead of scanning every lease.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * Reads are lock-free across all three indices. Writes go through {@link
 * ConcurrentHashMap#compute} on the primary index and update the secondary indices inside
 * the same compute lambda — they ride the per-key serialization the {@code compute}
 * primitive already provides.
 */
public final class LeaseRegistry {

    private static final Comparator<SagaLease> EXPIRES_AT_ORDER =
            Comparator.comparing(SagaLease::expiresAt).thenComparing(l -> l.sagaId().value());

    /**
     * Cached comparator used to build per-peer {@link ConcurrentSkipListSet} instances. Was
     * reconstructed inline via {@code Comparator.comparing(SagaId::value)} on every
     * computeIfAbsent miss; lifting to a static constant amortises the cost across every
     * peer.
     */
    private static final Comparator<SagaId> SAGA_ID_BY_VALUE_ORDER =
            Comparator.comparing(SagaId::value);

    private final Clock                                                    clock;
    private final ConcurrentHashMap<SagaId, SagaLease>                     leaseIndex            = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PeerId, ConcurrentSkipListSet<SagaId>> sagaIdsByPeerIndex    =
            new ConcurrentHashMap<>();
    private final ConcurrentSkipListSet<SagaLease>                         leasesByDeadlineIndex =
            new ConcurrentSkipListSet<>(EXPIRES_AT_ORDER);

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
        leaseIndex.compute(
                           observed.sagaId(),
                           (k, existing) -> {
                               if (existing == null) {
                                   addToSecondaryIndices(observed);
                                   return observed;
                               }
                               if (existing.expiresAt().isBefore(observed.expiresAt())) {
                                   removeFromSecondaryIndices(existing);
                                   addToSecondaryIndices(observed);
                                   return observed;
                               }
                               return existing;
                           });
    }

    /** Drop the registry entry for {@code sagaId}, e.g. when the saga terminates cleanly. */
    public void forget(SagaId sagaId) {
        Objects.requireNonNull(sagaId, "sagaId");
        SagaLease removed = leaseIndex.remove(sagaId);
        if (removed != null) {
            removeFromSecondaryIndices(removed);
        }
    }

    /** Current observed lease for {@code sagaId}, or {@link Optional#empty()} if unknown. */
    public Optional<SagaLease> lease(SagaId sagaId) {
        Objects.requireNonNull(sagaId, "sagaId");
        return Optional.ofNullable(leaseIndex.get(sagaId));
    }

    /**
     * Returns every lease this peer currently believes is owned by {@code peerId}. Looks the
     * peer's {@link SagaId} set up in {@link #sagaIdsByPeerIndex} and dereferences each id
     * through the primary index — O(K-for-peer) instead of the previous O(N-leases) full
     * scan.
     */
    public List<SagaLease> ownedBy(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId");
        ConcurrentSkipListSet<SagaId> sagaIds = sagaIdsByPeerIndex.get(peerId);
        if (sagaIds == null || sagaIds.isEmpty()) {
            return List.of();
        }
        List<SagaLease> result = new ArrayList<>(sagaIds.size());
        for (SagaId sagaId : sagaIds) {
            SagaLease lease = leaseIndex.get(sagaId);
            if (lease != null && lease.ownerPeerId().equals(peerId)) {
                result.add(lease);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns every lease whose {@code expiresAt} is at or before the registry's clock's
     * current instant — the candidates for a lease-claim attempt. Head-walks the deadline
     * skiplist in O(K-expired) and stops as soon as the head's deadline is in the future.
     */
    public List<SagaLease> expiredLeases() {
        Instant             now     = clock.instant();
        List<SagaLease>     matches = new ArrayList<>();
        Iterator<SagaLease> iter    = leasesByDeadlineIndex.iterator();
        while (iter.hasNext()) {
            SagaLease candidate = iter.next();
            if (!candidate.isExpired(now)) {
                break;
            }
            // Re-validate against the primary index — a concurrent observe() may have already
            // replaced this lease with a fresher one. Drop the stale reference in that case.
            SagaLease current = leaseIndex.get(candidate.sagaId());
            if (current == null) {
                leasesByDeadlineIndex.remove(candidate);
                continue;
            }
            if (!current.expiresAt().equals(candidate.expiresAt())) {
                // The skiplist still holds the stale reference; let observe() / forget() clean it
                // up. Walk past for this sweep.
                continue;
            }
            matches.add(current);
        }
        return List.copyOf(matches);
    }

    /** Snapshot of every known lease. */
    public Collection<SagaLease> snapshot() {
        return List.copyOf(leaseIndex.values());
    }

    /** Current count of tracked leases. */
    public int size() {
        return leaseIndex.size();
    }

    private void addToSecondaryIndices(SagaLease lease) {
        sagaIdsByPeerIndex
                .computeIfAbsent(lease.ownerPeerId(), _ -> new ConcurrentSkipListSet<>(SAGA_ID_BY_VALUE_ORDER))
                .add(lease.sagaId());
        leasesByDeadlineIndex.add(lease);
    }

    private void removeFromSecondaryIndices(SagaLease lease) {
        ConcurrentSkipListSet<SagaId> peerSet = sagaIdsByPeerIndex.get(lease.ownerPeerId());
        if (peerSet != null) {
            peerSet.remove(lease.sagaId());
        }
        leasesByDeadlineIndex.remove(lease);
    }
}

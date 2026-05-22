package net.nexus_flow.core.ring.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.nexus_flow.core.ring.transport.PeerId;
import org.jspecify.annotations.Nullable;

/**
 * Fair-distribution {@link PeerSelector}. Maintains a monotonic counter and picks
 * {@code candidates[counter % size]} after sorting the candidates deterministically so two
 * concurrent calls with the same candidate set produce the same ordering.
 *
 * <h2>Why deterministic sort instead of "natural iteration order"</h2>
 *
 * {@link Set} iteration order is unspecified — two pods with the same candidate set could
 * iterate in different orders, defeating round-robin fairness in cluster-aggregated metrics.
 * Sorting by {@link PeerId#value()} makes the round-robin sequence reproducible across pods.
 *
 * <h2>Sort caching</h2>
 *
 * The sorted candidate list is memoised by a fingerprint of the candidate set so steady-state
 * deployments (stable peer membership) pay O(K log K) once per distinct membership change
 * instead of per-select. The cache is bounded — when it fills, additional misses recompute
 * without caching. Membership churn that exceeds the cap degrades gracefully to "no cache",
 * matching {@link HashRingPeerSelector} behaviour.
 *
 * <h2>routingKey is ignored</h2>
 *
 * Round-robin does not use affinity — pass a hash-ring selector if you need stable mapping
 * from aggregate id to peer.
 */
public final class RoundRobinPeerSelector implements PeerSelector {

    private static final int MAX_CACHED_ORDERINGS = 64;

    private final AtomicLong                              counter               = new AtomicLong();
    private final ConcurrentHashMap<String, List<PeerId>> sortedCandidatesCache =
            new ConcurrentHashMap<>();

    @Override
    public Optional<PeerId> select(Set<PeerId> candidates, @Nullable String routingKey) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<PeerId> sorted = sortedFor(candidates);
        long         n      = counter.getAndIncrement();
        int          idx    = Math.floorMod(n, sorted.size());
        return Optional.of(sorted.get(idx));
    }

    private List<PeerId> sortedFor(Set<PeerId> candidates) {
        // Single materialise + sort. The fingerprint is derived from the SAME already-sorted
        // list (no second pass), the cache check happens after sort, and only on a cache
        // miss do we pay the immutable copy. Previously the code sorted twice (once via the
        // fingerprint helper, once for the selector).
        List<PeerId> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(PeerId::value));
        StringBuilder fp = new StringBuilder(sorted.size() * 8);
        for (PeerId p : sorted) {
            fp.append(p.value()).append('\n');
        }
        String       fingerprint = fp.toString();
        List<PeerId> cached      = sortedCandidatesCache.get(fingerprint);
        if (cached != null) {
            return cached;
        }
        List<PeerId> immutable = List.copyOf(sorted);
        if (sortedCandidatesCache.size() < MAX_CACHED_ORDERINGS) {
            List<PeerId> winner = sortedCandidatesCache.putIfAbsent(fingerprint, immutable);
            return winner == null ? immutable : winner;
        }
        return immutable;
    }
}

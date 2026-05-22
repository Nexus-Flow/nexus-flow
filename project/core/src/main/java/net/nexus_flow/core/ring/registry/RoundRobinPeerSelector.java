package net.nexus_flow.core.ring.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * <h2>routingKey is ignored</h2>
 *
 * Round-robin does not use affinity — pass a hash-ring selector if you need stable mapping
 * from aggregate id to peer.
 */
public final class RoundRobinPeerSelector implements PeerSelector {

    private final AtomicLong counter = new AtomicLong();

    @Override
    public Optional<PeerId> select(Set<PeerId> candidates, @Nullable String routingKey) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<PeerId> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted, (a, b) -> a.value().compareTo(b.value()));
        long n   = counter.getAndIncrement();
        int  idx = Math.floorMod(n, sorted.size());
        return Optional.of(sorted.get(idx));
    }
}

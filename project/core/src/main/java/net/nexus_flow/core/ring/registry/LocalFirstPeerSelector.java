package net.nexus_flow.core.ring.registry;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.nexus_flow.core.ring.transport.PeerId;
import org.jspecify.annotations.Nullable;

/**
 * Affinity-free {@link PeerSelector} decorator that prefers the local peer whenever the candidate
 * set already contains it. Any non-local pick is delegated to a wrapped selector — typically a
 * {@link RoundRobinPeerSelector} or {@link HashRingPeerSelector} — so the dispatch behaviour
 * remains identical to the underlying strategy for peers that genuinely need a network hop.
 *
 * <h2>Why this exists</h2>
 *
 * A handler-set replicated across the whole cluster (e.g. every pod registers
 * {@code PlaceOrderCommandHandler}) makes every dispatch through a hash-ring or round-robin
 * selector pay an avoidable network round-trip whenever the local pod is one of the candidates.
 * The local-first decorator collapses that to an in-process call and reserves the wire only for
 * commands the local pod cannot serve. In typical deployments where 80%+ of the candidate set
 * includes the caller, the latency saved per dispatch is the full mTLS-protected round-trip
 * (sub-millisecond on-network plus encode/decode); the throughput saved is the entire frame
 * encode/decode cycle.
 *
 * <h2>Composition with affinity selectors</h2>
 *
 * Decorating a {@link HashRingPeerSelector} weakens its global affinity guarantee — two pods
 * processing the same aggregate would each pick themselves instead of converging on the
 * hash-mapped owner. Use this decorator ONLY when:
 *
 * <ul>
 * <li>the workload has no aggregate-affinity requirement (the inner selector is intentionally
 * load-balancing, not affinity-pinning); OR
 * <li>the application enforces single-writer-per-aggregate through another mechanism (saga
 * lease, JDBC row lock, optimistic-concurrency rejection) and the affinity bias was only a
 * latency optimisation.
 * </ul>
 *
 * When wrapped around a round-robin selector the decorator is always safe — round-robin already
 * gives no affinity, only fair distribution among NON-local candidates.
 *
 * <h2>Thread-safety</h2>
 *
 * Stateless aside from the immutable {@code localPeerId} and the delegate. Concurrent calls do
 * not contend.
 */
public final class LocalFirstPeerSelector implements PeerSelector {

    private final PeerId       localPeerId;
    private final PeerSelector remoteFallback;

    /**
     * @param localPeerId    the id of this pod — preferred when present in the candidate set
     * @param remoteFallback selector consulted for non-local picks; never {@code null}
     */
    public LocalFirstPeerSelector(PeerId localPeerId, PeerSelector remoteFallback) {
        this.localPeerId    = Objects.requireNonNull(localPeerId, "localPeerId");
        this.remoteFallback = Objects.requireNonNull(remoteFallback, "remoteFallback");
    }

    /** Convenience factory: local-first over a fresh {@link RoundRobinPeerSelector}. */
    public static LocalFirstPeerSelector overRoundRobin(PeerId localPeerId) {
        return new LocalFirstPeerSelector(localPeerId, new RoundRobinPeerSelector());
    }

    @Override
    public Optional<PeerId> select(Set<PeerId> candidates, @Nullable String routingKey) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.contains(localPeerId)) {
            return Optional.of(localPeerId);
        }
        return remoteFallback.select(candidates, routingKey);
    }

    /** @return the local peer id this selector prefers. */
    public PeerId localPeerId() {
        return localPeerId;
    }

    /** @return the selector used for non-local picks. */
    public PeerSelector remoteFallback() {
        return remoteFallback;
    }
}

package net.nexus_flow.core.ring.membership;

import java.util.Collection;
import java.util.Optional;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Read API for the membership view. The membership strategy (static, SWIM, external registry)
 * maintains the underlying state; higher layers consume it through this interface.
 *
 * <p>Implementations MUST be safe for concurrent reads from any thread. Returning a defensive
 * snapshot (immutable {@link Collection}) is required — callers iterate without coordination.
 */
public interface MembershipRegistry {

    /**
     * @return an immutable snapshot of every peer the registry currently knows about,
     *         including {@link PeerState#CONFIRMED_DEAD} / {@link PeerState#LEFT} entries. To get
     *         only routable peers, filter by {@link PeerState#isRoutable()}.
     */
    Collection<PeerInfo> peers();

    /**
     * Cached subset of {@link #peers()} containing only peers whose current
     * {@link PeerInfo#state()} is {@link PeerState#ALIVE}. Ring bridges (event fan-out,
     * outbox replay broadcast) call this on every published frame; serving a pre-filtered
     * snapshot eliminates one O(N-peers) filter pass per frame.
     *
     * <p><strong>Default implementation:</strong> filters {@link #peers()} on every call.
     * Implementations SHOULD override with a cache invalidated by state transitions so the
     * common fan-out path pays a single volatile read.
     *
     * @return immutable snapshot of currently-{@link PeerState#ALIVE} peers; never
     *         {@code null}
     */
    default Collection<PeerInfo> alivePeers() {
        java.util.List<PeerInfo> alive = new java.util.ArrayList<>(peers().size());
        for (PeerInfo p : peers()) {
            if (p.state() == PeerState.ALIVE) {
                alive.add(p);
            }
        }
        return java.util.List.copyOf(alive);
    }

    /**
     * @param peerId the peer to look up
     * @return the peer's info, or {@link Optional#empty()} if unknown
     */
    Optional<PeerInfo> peer(PeerId peerId);

    /**
     * Subscribe to membership events. The listener is invoked synchronously for every state
     * transition. Returns a handle the caller closes to unsubscribe.
     *
     * @param listener the listener; must not be {@code null}
     * @return a handle; calling {@link Subscription#close()} unsubscribes
     */
    Subscription subscribe(MembershipListener listener);

    /** Handle returned by {@link #subscribe(MembershipListener)}. Calling close() unsubscribes. */
    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}

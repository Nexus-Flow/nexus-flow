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

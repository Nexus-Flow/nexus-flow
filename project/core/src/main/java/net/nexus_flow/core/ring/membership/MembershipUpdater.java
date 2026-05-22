package net.nexus_flow.core.ring.membership;

import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Mutation-side SPI for membership backends. Discovery implementations
 * ({@link StaticPeerListMembership static list}, a future
 * {@code nexus-flow-membership-swim} module, k8s-API watchers, Consul integrations) push
 * peer events through this interface; the framework's
 * {@link DefaultMembershipRegistry} implements it and propagates changes to subscribers.
 *
 * <h2>Why a separate interface from {@link MembershipRegistry}</h2>
 *
 * {@link MembershipRegistry} is the READ side — what consumers (heartbeat detector,
 * routing, saga lease coordinator, dispatch) subscribe to. {@code MembershipUpdater} is
 * the WRITE side — what discovery layers call. Separating them gives:
 *
 * <ul>
 * <li>A clear contract for adapter modules: "your job is to call register/transition/
 * unregister; the framework's listeners pick up your work".
 * <li>Layering: an adapter module never instantiates a registry of its own; it receives
 * the framework's registry via DI and treats it as an updater.
 * <li>Testability: tests fake the updater alone instead of the full registry.
 * </ul>
 *
 * <h2>SWIM module readiness</h2>
 *
 * A future {@code nexus-flow-membership-swim} module ships a {@link MembershipStrategy}
 * whose internals run the SWIM protocol (UDP gossip, suspicion timer, indirect ping). It
 * receives a {@link MembershipUpdater} at construction and pushes every confirmed
 * observation through it — never touching the registry directly. The framework's other
 * subsystems consume the registry without needing to know that SWIM is the discovery
 * source.
 *
 * <h2>Thread-safety</h2>
 *
 * Implementations MUST be safe for concurrent calls from any thread. Updates are
 * observable through {@link MembershipRegistry#subscribe} synchronously — listeners run
 * on the caller's thread.
 */
public interface MembershipUpdater {

    /**
     * Register a peer with initial state {@link PeerState#JOINING}. No-op if the peer is
     * already known (does NOT reset the state).
     *
     * @param peerId  the peer's stable handle; must not be {@code null}
     * @param address the peer's network address; must not be {@code null}
     * @return the {@link PeerInfo} after the register (existing entry if already known)
     */
    PeerInfo register(PeerId peerId, PeerAddress address);

    /**
     * Transition {@code peerId} to {@code newState}. Throws
     * {@link IllegalArgumentException} when the peer is unknown so discovery layers
     * surface "ghost transitions" loudly instead of silently dropping them.
     *
     * @param peerId   the peer; must not be {@code null}
     * @param newState the new state; must not be {@code null}
     * @return the {@link PeerInfo} after the transition
     */
    PeerInfo transition(PeerId peerId, PeerState newState);

    /**
     * Record that the framework received a PONG (or any liveness signal) from {@code peerId}.
     * The implementation refreshes the peer's last-seen instant; a SUSPECT peer is
     * promoted to ALIVE. No-op when the peer is unknown.
     *
     * @param peerId the peer; must not be {@code null}
     */
    void recordPong(PeerId peerId);

    /**
     * Remove {@code peerId} from the registry. Use when a discovery layer learns the peer
     * has been decommissioned (left the k8s service, drained from a Consul deregister)
     * and should disappear from the routing surface entirely. Subscribers receive a
     * {@link MembershipEvent.PeerLeft} with {@code cleanShutdown = true}.
     *
     * @param peerId the peer; must not be {@code null}
     */
    void unregister(PeerId peerId);
}

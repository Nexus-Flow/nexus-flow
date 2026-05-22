package net.nexus_flow.core.ring.membership;

import java.time.Instant;
import java.util.Objects;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import org.jspecify.annotations.Nullable;

/**
 * Immutable snapshot of one peer's membership view: identity, address, lifecycle state, and
 * the most recent transition timestamp. Returned by {@link MembershipRegistry#peer(PeerId)} /
 * {@link MembershipRegistry#peers()} so consumers see a consistent view without the registry
 * having to expose its mutable internal state.
 *
 * @param peerId           stable peer id
 * @param address          network address
 * @param state            current lifecycle state
 * @param lastTransitionAt instant of the last state transition
 * @param lastPongAt       instant of the last successful pong; {@code null} if no pong has been
 *                         received yet (typically the first heartbeat round)
 */
public record PeerInfo(
                       PeerId peerId,
                       PeerAddress address,
                       PeerState state,
                       Instant lastTransitionAt,
                       @Nullable Instant lastPongAt) {

    public PeerInfo {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(lastTransitionAt, "lastTransitionAt");
    }
}

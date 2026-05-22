package net.nexus_flow.core.ring.membership;

import java.time.Instant;
import java.util.Objects;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Sealed sum type of membership-state-change events emitted by a {@link MembershipRegistry}.
 * Higher layers ({@link MembershipListener} subscribers) pattern-match on the variants:
 *
 * <pre>{@code
 * switch (event) {
 *     case PeerJoined j -> directory.addPeer(j.peerId(), j.address());
 *     case PeerSuspected s -> metrics.markSuspect(s.peerId());
 *     case PeerLeft l -> directory.removePeer(l.peerId());
 *     case PeerRecovered r -> metrics.markRecovered(r.peerId());
 * }
 * }</pre>
 */
public sealed interface MembershipEvent {

    /** The peer the event concerns. */
    PeerId peerId();

    /** The wall-clock instant the event was emitted. */
    Instant at();

    /**
     * A peer became {@link PeerState#ALIVE} for the first time (handshake completed). The
     * peer's address is included so subscribers can wire routing tables without needing a
     * separate registry lookup.
     */
    record PeerJoined(PeerId peerId, PeerAddress address, Instant at) implements MembershipEvent {
        public PeerJoined {
            Objects.requireNonNull(peerId, "peerId");
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * Peer was marked {@link PeerState#SUSPECT} after one or more missed heartbeats. Soft
     * signal — routing avoids the peer but in-flight work is not yet aborted.
     */
    record PeerSuspected(PeerId peerId, Instant at) implements MembershipEvent {
        public PeerSuspected {
            Objects.requireNonNull(peerId, "peerId");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * Peer transitioned out of {@link PeerState#SUSPECT} back to {@link PeerState#ALIVE} after
     * a successful pong. Lets subscribers clear soft warnings emitted on {@link PeerSuspected}.
     */
    record PeerRecovered(PeerId peerId, Instant at) implements MembershipEvent {
        public PeerRecovered {
            Objects.requireNonNull(peerId, "peerId");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * Peer is gone — either {@link PeerState#CONFIRMED_DEAD} (failure detector timeout) or
     * {@link PeerState#LEFT} (clean shutdown signaled via PEER_LEFT). The {@code cleanShutdown}
     * flag discriminates the two so dashboards can count "detected failure" vs "planned
     * departure" separately.
     */
    record PeerLeft(PeerId peerId, boolean cleanShutdown, Instant at) implements MembershipEvent {
        public PeerLeft {
            Objects.requireNonNull(peerId, "peerId");
            Objects.requireNonNull(at, "at");
        }
    }
}

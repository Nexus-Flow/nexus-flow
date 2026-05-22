package net.nexus_flow.core.ring.membership;

/**
 * Lifecycle state of a known peer in the membership view.
 *
 * <pre>
 * JOINING ──handshake ok──▶ ALIVE
 * ALIVE ──missed heartbeats──▶ SUSPECT
 * SUSPECT ──pong received──▶ ALIVE
 * SUSPECT ──suspect timeout──▶ CONFIRMED_DEAD
 * ALIVE ──clean shutdown──▶ LEFT
 * CONFIRMED_DEAD ──rejoin handshake──▶ JOINING
 * LEFT ──rejoin handshake──▶ JOINING
 * </pre>
 *
 * <p>The {@code SUSPECT} state is a separator: it lets the membership emit a soft signal
 * (route around me, but don't kill saga leases yet) while the failure detector waits for the
 * suspect timeout. {@code CONFIRMED_DEAD} is the hard signal — sagas are reassigned, events
 * waiting in the outbox for this peer are released for another consumer.
 */
public enum PeerState {

    /** Handshake in progress; not yet ready to receive application frames. */
    JOINING,

    /** Healthy — receiving frames, responding to pings. */
    ALIVE,

    /**
     * Missed one or more heartbeats. Soft signal: routing avoids this peer where possible
     * but in-flight work is not yet aborted. A successful pong restores {@link #ALIVE}.
     */
    SUSPECT,

    /**
     * Failure detector has given up. Hard signal: connection closed, sagas reassigned, events
     * targeted at this peer are released for another consumer.
     */
    CONFIRMED_DEAD,

    /**
     * Peer left cleanly via a {@link net.nexus_flow.core.ring.wire.FrameType#PEER_LEFT}
     * frame. Same routing semantics as {@link #CONFIRMED_DEAD} but distinguished in metrics
     * (clean shutdown vs detected failure).
     */
    LEFT;

    /** Is this state observable as "the peer is gone" by routing decisions? */
    public boolean isGone() {
        return this == CONFIRMED_DEAD || this == LEFT;
    }

    /** Is this state observable as "the peer is healthy enough to route to" by routing? */
    public boolean isRoutable() {
        return this == ALIVE;
    }
}

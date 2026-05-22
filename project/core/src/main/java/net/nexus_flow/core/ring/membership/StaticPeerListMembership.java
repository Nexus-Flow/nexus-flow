package net.nexus_flow.core.ring.membership;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Membership strategy that uses a FIXED list of peers from configuration. No autodiscovery, no
 * gossip — the operator supplies the list at construction and the registry never adds peers
 * outside that list.
 *
 * <h2>When to use this</h2>
 *
 * <ul>
 * <li><strong>Sidecar deployments</strong> — every app connects to {@code localhost:N} where
 * a sidecar handles the rest of the world. The "list" has one entry: the sidecar.
 * <li><strong>Small fixed clusters</strong> where pod IPs are pinned (StatefulSet with
 * headless service) and the operator prefers explicit configuration over autodiscovery.
 * <li><strong>Tests</strong> — exact peer set known at JVM startup.
 * </ul>
 *
 * For dynamic clusters where peers join and leave on their own schedule, use the SWIM
 * gossip strategy (R4b, future phase).
 *
 * <h2>State transitions</h2>
 *
 * Every configured peer is registered as {@link PeerState#JOINING} on {@link #start()}. Higher
 * layers wire the actual dial + handshake (R2/R3 transport) and call {@link
 * DefaultMembershipRegistry#transition(PeerId, PeerState) registry.transition(peer, ALIVE)}
 * when the handshake completes. This strategy does NOT own the dialing — it just maintains the
 * roster and exposes the registry mutation API for the transport layer to drive.
 *
 * <p>{@link #shutdown()} marks every peer {@link PeerState#LEFT}.
 */
public final class StaticPeerListMembership implements MembershipStrategy {

    private final DefaultMembershipRegistry registry;
    private final Map<PeerId, PeerAddress>  seedPeers;
    private final AtomicBoolean             started = new AtomicBoolean();
    private final AtomicBoolean             stopped = new AtomicBoolean();

    /**
     * @param clock     clock for transition timestamps; must not be {@code null}
     * @param seedPeers immutable map of seed peers; must not be {@code null} or empty. The
     *                  map iteration order is preserved for tests / diagnostics that rely on registration
     *                  order
     */
    public StaticPeerListMembership(Clock clock, Map<PeerId, PeerAddress> seedPeers) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(seedPeers, "seedPeers");
        if (seedPeers.isEmpty()) {
            throw new IllegalArgumentException("seedPeers must not be empty");
        }
        this.registry  = new DefaultMembershipRegistry(clock);
        this.seedPeers = Map.copyOf(seedPeers);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true))
            return;
        for (Map.Entry<PeerId, PeerAddress> entry : seedPeers.entrySet()) {
            registry.register(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void shutdown() {
        if (!stopped.compareAndSet(false, true))
            return;
        for (PeerId peerId : seedPeers.keySet()) {
            try {
                registry.transition(peerId, PeerState.LEFT);
            } catch (IllegalArgumentException ignored) {
                // peer was never registered (start() never called) — fine
            }
        }
    }

    @Override
    public MembershipRegistry registry() {
        return registry;
    }

    /**
     * Returns the underlying {@link DefaultMembershipRegistry} so the transport layer (R5
     * RingEventBus binding, R6 directory) can drive transitions. Exposed as the concrete
     * type intentionally — the mutation API ({@code transition}, {@code recordPong}) is not
     * part of the {@link MembershipRegistry} read interface.
     */
    public DefaultMembershipRegistry mutableRegistry() {
        return registry;
    }

    /** Immutable view of the configured seed peers. */
    public List<PeerId> seedPeerIds() {
        return List.copyOf(seedPeers.keySet());
    }
}

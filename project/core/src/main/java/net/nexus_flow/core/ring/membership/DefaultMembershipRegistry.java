package net.nexus_flow.core.ring.membership;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Concrete {@link MembershipRegistry} implementation backed by a {@link ConcurrentHashMap} of
 * peers and a {@link CopyOnWriteArrayList} of listeners. The map is the single source of truth
 * for membership state; every transition goes through {@link #transition(PeerId, PeerState)}
 * so listener dispatch and state mutation cannot race.
 *
 * <h2>Listener invocation policy</h2>
 *
 * Listeners are invoked SYNCHRONOUSLY on the calling thread (typically the membership
 * strategy's heartbeat tick thread, or the inbound HELLO handler). A listener that throws is
 * logged at WARNING but does NOT unsubscribe — partial-failure of one listener should not
 * silently break the others. Long-running reactions MUST dispatch to a separate executor.
 *
 * <h2>Thread safety</h2>
 *
 * {@link #transition(PeerId, PeerState)} is synchronized on the peer's map entry via
 * {@link ConcurrentHashMap#compute}. Reads of {@link #peers()} / {@link #peer(PeerId)} are
 * lock-free (snapshot from the concurrent map). Listener add/remove is lock-free
 * (CopyOnWriteArrayList).
 */
public final class DefaultMembershipRegistry implements MembershipRegistry, MembershipUpdater {

    private static final System.Logger LOG =
            System.getLogger(DefaultMembershipRegistry.class.getName());

    private final Clock                                    clock;
    private final ConcurrentHashMap<PeerId, PeerInfo>      peers     = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<MembershipListener> listeners = new CopyOnWriteArrayList<>();
    /**
     * Lazy snapshot of {@link #peers}, materialised on first read and invalidated by every
     * mutation. The hot path of {@link #peers()} — called by the heartbeat tick, health
     * checker, ring bridges — pays a single {@code volatile} read in steady state instead of
     * a per-call {@link List#copyOf} of the underlying map.
     */
    private volatile Collection<PeerInfo>                  cachedPeerSnapshot;
    /**
     * Lazy ALIVE-only sub-snapshot. Ring event bridges call {@link #alivePeers()} on every
     * published frame; the cache keeps that path at a single volatile read in steady state.
     */
    private volatile Collection<PeerInfo>                  cachedAlivePeerSnapshot;

    /**
     * @param clock clock for transition timestamps; must not be {@code null}. Injecting the
     *              clock lets tests use a {@link Clock#fixed(Instant, java.time.ZoneId) fixed clock}
     *              for deterministic assertions on transition times.
     */
    public DefaultMembershipRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Register a peer with initial state {@link PeerState#JOINING}. No-op if the peer is
     * already known (does NOT reset the state). Returns the resulting {@link PeerInfo}.
     */
    @Override
    public PeerInfo register(PeerId peerId, PeerAddress address) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(address, "address");
        PeerInfo result = peers.computeIfAbsent(
                                                peerId,
                                                k -> new PeerInfo(k, address, PeerState.JOINING, clock.instant(), null));
        cachedPeerSnapshot      = null;
        cachedAlivePeerSnapshot = null;
        return result;
    }

    /**
     * Mutate the peer's state. Dispatches the appropriate {@link MembershipEvent} to listeners
     * if the transition is observable (e.g. {@code JOINING → ALIVE} emits {@link
     * MembershipEvent.PeerJoined}; {@code ALIVE → SUSPECT} emits {@link
     * MembershipEvent.PeerSuspected}; etc.).
     *
     * @param peerId   the peer; must be already-registered
     * @param newState the target state
     * @return the new {@link PeerInfo} after the transition
     * @throws IllegalArgumentException if the peer is not registered
     */
    @Override
    public PeerInfo transition(PeerId peerId, PeerState newState) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(newState, "newState");
        Instant  now        = clock.instant();
        var      transition = new TransitionContext();
        PeerInfo result     = peers.computeIfPresent(
                                                     peerId,
                                                     (k, existing) -> {
                                                         if (existing.state() == newState) {
                                                             transition.event = null;
                                                             return existing;
                                                         }
                                                         transition.event = eventFor(existing, newState, now);
                                                         return new PeerInfo(existing.peerId(), existing.address(), newState, now, existing
                                                                 .lastPongAt());
                                                     });
        if (result == null) {
            throw new IllegalArgumentException(
                    "cannot transition unregistered peer " + peerId + " to " + newState);
        }
        cachedPeerSnapshot      = null;
        cachedAlivePeerSnapshot = null;
        if (transition.event != null) {
            dispatch(transition.event);
        }
        return result;
    }

    /**
     * Remove {@code peerId} from the registry. Emits a {@link MembershipEvent.PeerLeft}
     * with {@code cleanShutdown=true} when the peer was registered. No-op when unknown.
     */
    @Override
    public void unregister(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId");
        Instant  now     = clock.instant();
        PeerInfo removed = peers.remove(peerId);
        if (removed != null) {
            cachedPeerSnapshot      = null;
            cachedAlivePeerSnapshot = null;
            dispatch(new MembershipEvent.PeerLeft(peerId, true, now));
        }
    }

    /**
     * Record a successful pong from this peer. Updates {@code lastPongAt} and, if the peer
     * was SUSPECT, transitions back to ALIVE (emitting {@link MembershipEvent.PeerRecovered}).
     */
    @Override
    public void recordPong(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId");
        Instant now        = clock.instant();
        var     transition = new TransitionContext();
        peers.computeIfPresent(
                               peerId,
                               (k, existing) -> {
                                   PeerState target =
                                           existing.state() == PeerState.SUSPECT ? PeerState.ALIVE : existing.state();
                                   if (target != existing.state()) {
                                       transition.event = new MembershipEvent.PeerRecovered(peerId, now);
                                   }
                                   return new PeerInfo(
                                           existing.peerId(), existing.address(), target, existing.lastTransitionAt(), now);
                               });
        cachedPeerSnapshot      = null;
        cachedAlivePeerSnapshot = null;
        if (transition.event != null) {
            dispatch(transition.event);
        }
    }

    private MembershipEvent eventFor(PeerInfo before, PeerState after, Instant at) {
        if (before.state() == PeerState.JOINING && after == PeerState.ALIVE) {
            return new MembershipEvent.PeerJoined(before.peerId(), before.address(), at);
        }
        if (after == PeerState.SUSPECT) {
            return new MembershipEvent.PeerSuspected(before.peerId(), at);
        }
        if (after == PeerState.ALIVE && before.state() == PeerState.SUSPECT) {
            return new MembershipEvent.PeerRecovered(before.peerId(), at);
        }
        if (after.isGone()) {
            return new MembershipEvent.PeerLeft(before.peerId(), after == PeerState.LEFT, at);
        }
        return null;
    }

    private void dispatch(MembershipEvent event) {
        for (MembershipListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Throwable t) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () -> "membership listener threw on " + event + ": " + t.getMessage(),
                        t);
            }
        }
    }

    @Override
    public Collection<PeerInfo> peers() {
        Collection<PeerInfo> snapshot = cachedPeerSnapshot;
        if (snapshot != null) {
            return snapshot;
        }
        Collection<PeerInfo> fresh = List.copyOf(peers.values());
        cachedPeerSnapshot = fresh;
        return fresh;
    }

    @Override
    public Collection<PeerInfo> alivePeers() {
        Collection<PeerInfo> snapshot = cachedAlivePeerSnapshot;
        if (snapshot != null) {
            return snapshot;
        }
        java.util.List<PeerInfo> alive = new java.util.ArrayList<>();
        for (PeerInfo p : peers.values()) {
            if (p.state() == PeerState.ALIVE) {
                alive.add(p);
            }
        }
        Collection<PeerInfo> fresh = List.copyOf(alive);
        cachedAlivePeerSnapshot = fresh;
        return fresh;
    }

    @Override
    public Optional<PeerInfo> peer(PeerId peerId) {
        return Optional.ofNullable(peers.get(peerId));
    }

    @Override
    public Subscription subscribe(MembershipListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Mutable carrier so the inside of {@link ConcurrentHashMap#compute} can hand out the
     * event for the outer dispatch without using AtomicReference (which would defeat the
     * single-allocation pattern).
     */
    private static final class TransitionContext {
        @org.jspecify.annotations.Nullable MembershipEvent event;
    }
}

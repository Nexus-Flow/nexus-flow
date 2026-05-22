package net.nexus_flow.core.ring.transport;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * PeerId → RingConnection lookup. The single source of truth for "which open connection
 * should I use to talk to peer X". Populated by the handshake handlers (acceptor + dialer
 * side) and consumed by every component that sends to a specific peer (heartbeat detector,
 * dispatcher, event bridge, lease coordinator).
 *
 * <h2>Concurrency</h2>
 *
 * Backed by a {@link ConcurrentHashMap}. Reads are lock-free. {@link #register} +
 * {@link #unregister} are O(1).
 *
 * <h2>Identity rules</h2>
 *
 * If a second connection registers for an already-known peerId (e.g. the peer reconnected
 * before the old connection was reaped) {@link #register} REPLACES the previous mapping and
 * returns the prior connection so the caller can close it. The registry does NOT close
 * connections itself — ownership of the {@link RingConnection} lifecycle stays with
 * whoever created it.
 */
public final class RingConnectionRegistry {

    private final ConcurrentHashMap<PeerId, RingConnection> byPeerId = new ConcurrentHashMap<>();

    /**
     * Register a connection for a peer. If the peer already has a registered connection it
     * is replaced and returned so the caller can close the stale one (typically: the new
     * handshake means the old connection was orphaned).
     *
     * @param peerId     the peer to register the connection for; must not be {@code null}
     * @param connection the live connection; must not be {@code null}
     * @return the previously-registered connection (caller closes), or {@code null} if this
     *         is the first registration for {@code peerId}
     */
    public @Nullable RingConnection register(PeerId peerId, RingConnection connection) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(connection, "connection");
        return byPeerId.put(peerId, connection);
    }

    /**
     * Remove the registration for {@code peerId}. No-op if absent. Returns the removed
     * connection so the caller can decide whether to close it (typically the caller is the
     * close path itself and the connection is already closing).
     */
    public @Nullable RingConnection unregister(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId");
        return byPeerId.remove(peerId);
    }

    /**
     * Atomic unregister-if-still-this-connection. Used by close paths to avoid clobbering a
     * fresh reconnect that registered a new connection between {@code close()} firing and
     * the unregister landing.
     *
     * @return {@code true} if the registration was removed
     */
    public boolean unregisterIf(PeerId peerId, RingConnection expected) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(expected, "expected");
        return byPeerId.remove(peerId, expected);
    }

    /** Look up the live connection for {@code peerId}, or {@link Optional#empty()} if none. */
    public Optional<RingConnection> get(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId");
        return Optional.ofNullable(byPeerId.get(peerId));
    }

    /** Immutable snapshot of every currently-registered peer. */
    public Collection<PeerId> peerIds() {
        return List.copyOf(byPeerId.keySet());
    }

    /** Immutable snapshot of every currently-registered connection. */
    public Collection<RingConnection> connections() {
        return List.copyOf(byPeerId.values());
    }

    /** Count of registered connections. */
    public int size() {
        return byPeerId.size();
    }
}

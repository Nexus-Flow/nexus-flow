package net.nexus_flow.core.ring.event;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Per-peer cursor of "last outbox sequence I've sent to this peer". Persisted across pod
 * restarts (caller wires the read/write via {@link
 * net.nexus_flow.core.ring.ops.PersistedRingStateStore} from R9 — this class is the in-memory
 * working copy).
 *
 * <h2>Why per-peer instead of one global cursor</h2>
 *
 * Different peers reconnect at different times — a sender holding ONE global cursor would
 * replay events to a peer that already saw them just because another peer hadn't. Per-peer
 * cursors mean the sender only re-sends what THIS specific peer is missing.
 *
 * <h2>Monotonic advance</h2>
 *
 * {@link #advance(PeerId, long)} is monotonic: a smaller value than the current is silently
 * ignored. This prevents out-of-order writes from a buggy caller from regressing the cursor
 * and causing duplicate redelivery.
 */
public final class PeerCursorTracker {

    private final ConcurrentHashMap<PeerId, Long> cursors = new ConcurrentHashMap<>();

    /** Initialise the tracker with cursors loaded from persistent storage on startup. */
    public void seed(Map<PeerId, Long> persisted) {
        Objects.requireNonNull(persisted, "persisted");
        for (Map.Entry<PeerId, Long> e : persisted.entrySet()) {
            advance(e.getKey(), e.getValue());
        }
    }

    /**
     * Advance the cursor for {@code peerId} to {@code seen}. No-op if {@code seen <=} the
     * current cursor (monotonic).
     */
    public void advance(PeerId peerId, long seen) {
        Objects.requireNonNull(peerId, "peerId");
        if (seen < 0) {
            throw new IllegalArgumentException("seen must be >= 0: " + seen);
        }
        cursors.merge(peerId, seen, Math::max);
    }

    /**
     * Returns the cursor for {@code peerId}, or 0 if no cursor has been recorded yet (the
     * peer has never received an event from this sender, so the replay must start from the
     * beginning).
     */
    public long cursor(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId");
        return cursors.getOrDefault(peerId, 0L);
    }

    /**
     * Drop the cursor for {@code peerId} — typically when the peer is confirmed dead and we
     * want to start fresh if they ever rejoin under the same id. Returns the removed cursor
     * or {@code null} if absent.
     */
    public Long forget(PeerId peerId) {
        Objects.requireNonNull(peerId, "peerId");
        return cursors.remove(peerId);
    }

    /** Snapshot of every cursor for persisting to disk. */
    public Map<PeerId, Long> snapshot() {
        return Map.copyOf(cursors);
    }

    /** Snapshot of just the peer ids. */
    public Collection<PeerId> trackedPeers() {
        return List.copyOf(cursors.keySet());
    }

    /** Current count of tracked peer cursors. */
    public int size() {
        return cursors.size();
    }
}

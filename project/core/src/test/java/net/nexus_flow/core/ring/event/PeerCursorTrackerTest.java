package net.nexus_flow.core.ring.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import net.nexus_flow.core.ring.transport.PeerId;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PeerCursorTracker} monotonicity + snapshot semantics. Cursor regression would
 * cause duplicate redelivery on reconnect.
 */
class PeerCursorTrackerTest {

    @Test
    void advance_recordsCursor_unknownPeerStartsAtZero() {
        PeerCursorTracker t = new PeerCursorTracker();
        assertEquals(0L, t.cursor(PeerId.of("p")));
        t.advance(PeerId.of("p"), 42L);
        assertEquals(42L, t.cursor(PeerId.of("p")));
    }

    @Test
    void advance_isMonotonic_smallerValueIgnored() {
        PeerCursorTracker t = new PeerCursorTracker();
        t.advance(PeerId.of("p"), 100L);
        t.advance(PeerId.of("p"), 50L); // older — ignored
        assertEquals(100L, t.cursor(PeerId.of("p")));
    }

    @Test
    void advance_negativeSeen_rejected() {
        PeerCursorTracker t = new PeerCursorTracker();
        assertThrows(IllegalArgumentException.class, () -> t.advance(PeerId.of("p"), -1L));
    }

    @Test
    void forget_removesEntry_returnsLastValue() {
        PeerCursorTracker t = new PeerCursorTracker();
        t.advance(PeerId.of("p"), 7L);
        assertEquals(7L, t.forget(PeerId.of("p")));
        assertNull(t.forget(PeerId.of("p")));
    }

    @Test
    void seed_initializesFromPersistedMap() {
        PeerCursorTracker t         = new PeerCursorTracker();
        Map<PeerId, Long> persisted = new LinkedHashMap<>();
        persisted.put(PeerId.of("a"), 10L);
        persisted.put(PeerId.of("b"), 20L);
        t.seed(persisted);
        assertEquals(10L, t.cursor(PeerId.of("a")));
        assertEquals(20L, t.cursor(PeerId.of("b")));
    }

    @Test
    void snapshot_isIndependentOfFurtherMutations() {
        PeerCursorTracker t = new PeerCursorTracker();
        t.advance(PeerId.of("p"), 1L);
        Map<PeerId, Long> snap = t.snapshot();
        t.advance(PeerId.of("q"), 2L);
        assertEquals(1, snap.size());
    }

    @Test
    void trackedPeers_returnsImmutableSnapshot() {
        PeerCursorTracker t = new PeerCursorTracker();
        t.advance(PeerId.of("a"), 1L);
        t.advance(PeerId.of("b"), 2L);
        var peers = t.trackedPeers();
        assertEquals(2, peers.size());
        assertTrue(peers.contains(PeerId.of("a")));
        assertTrue(peers.contains(PeerId.of("b")));
    }
}

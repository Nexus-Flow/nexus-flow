package net.nexus_flow.core.ring.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RingConnectionRegistry} contract: register/unregister/get + replace semantics +
 * conditional unregister-if. Uses a stub RingConnection backed by a {@link
 * java.net.Socket} that has its streams replaced with closed ones — we never actually do I/O.
 */
class RingConnectionRegistryTest {

    private static RingConnection stubConnection() throws IOException {
        return TestRingConnections.stub();
    }

    @Test
    void register_storesConnection_lookupReturnsIt() throws IOException {
        RingConnectionRegistry reg  = new RingConnectionRegistry();
        RingConnection         conn = stubConnection();
        assertNull(reg.register(PeerId.of("a"), conn));
        assertSame(conn, reg.get(PeerId.of("a")).orElseThrow());
    }

    @Test
    void register_returningPriorConnection_letsCallerCloseStale() throws IOException {
        RingConnectionRegistry reg    = new RingConnectionRegistry();
        RingConnection         first  = stubConnection();
        RingConnection         second = stubConnection();
        reg.register(PeerId.of("a"), first);
        assertSame(first, reg.register(PeerId.of("a"), second));
        assertSame(second, reg.get(PeerId.of("a")).orElseThrow());
    }

    @Test
    void unregister_returnsRemovedConnection() throws IOException {
        RingConnectionRegistry reg  = new RingConnectionRegistry();
        RingConnection         conn = stubConnection();
        reg.register(PeerId.of("a"), conn);
        assertSame(conn, reg.unregister(PeerId.of("a")));
        assertTrue(reg.get(PeerId.of("a")).isEmpty());
    }

    @Test
    void unregisterIf_atomicCheck_protectsAgainstReconnectRace() throws IOException {
        RingConnectionRegistry reg       = new RingConnectionRegistry();
        RingConnection         original  = stubConnection();
        RingConnection         reconnect = stubConnection();
        reg.register(PeerId.of("a"), original);
        // Reconnect happens BEFORE the original close path's unregisterIf runs.
        reg.register(PeerId.of("a"), reconnect);
        // unregisterIf for the stale connection must NOT clobber the reconnect.
        assertFalse(reg.unregisterIf(PeerId.of("a"), original));
        assertSame(reconnect, reg.get(PeerId.of("a")).orElseThrow());
    }

    @Test
    void peerIds_returnsSnapshot_safeForConcurrentMutation() throws IOException {
        RingConnectionRegistry reg = new RingConnectionRegistry();
        reg.register(PeerId.of("a"), stubConnection());
        reg.register(PeerId.of("b"), stubConnection());
        var snapshot = reg.peerIds();
        assertEquals(2, snapshot.size());
        reg.register(PeerId.of("c"), stubConnection());
        // Snapshot is unchanged.
        assertEquals(2, snapshot.size());
    }

    @Test
    void size_tracksRegistrations() throws IOException {
        RingConnectionRegistry reg = new RingConnectionRegistry();
        assertEquals(0, reg.size());
        reg.register(PeerId.of("a"), stubConnection());
        assertEquals(1, reg.size());
        reg.unregister(PeerId.of("a"));
        assertEquals(0, reg.size());
    }

    @Test
    void nullArgs_throw() {
        RingConnectionRegistry reg = new RingConnectionRegistry();
        assertThrows(NullPointerException.class, () -> reg.register(null, null));
        assertThrows(NullPointerException.class, () -> reg.unregister(null));
        assertThrows(NullPointerException.class, () -> reg.get(null));
    }
}

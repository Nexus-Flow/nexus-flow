package net.nexus_flow.core.ring.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.nexus_flow.core.ring.transport.PeerAddress;
import net.nexus_flow.core.ring.transport.PeerId;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link StaticPeerListMembership} state machine and event dispatch. Uses a fixed
 * clock so transition timestamps are deterministic.
 */
class StaticPeerListMembershipTest {

    private static final Clock T0 = Clock.fixed(Instant.parse("2026-05-25T10:00:00Z"), ZoneOffset.UTC);

    private static Map<PeerId, PeerAddress> twoPeers() {
        Map<PeerId, PeerAddress> map = new LinkedHashMap<>();
        map.put(PeerId.of("alpha"), PeerAddress.loopback(9001));
        map.put(PeerId.of("beta"), PeerAddress.loopback(9002));
        return map;
    }

    @Test
    void start_registersAllSeeds_asJoining() {
        StaticPeerListMembership s = new StaticPeerListMembership(T0, twoPeers());
        s.start();
        assertEquals(2, s.registry().peers().size());
        s.registry().peers().forEach(p -> assertEquals(PeerState.JOINING, p.state()));
    }

    @Test
    void transition_joining_to_alive_emitsPeerJoined() {
        StaticPeerListMembership s = new StaticPeerListMembership(T0, twoPeers());
        s.start();
        List<MembershipEvent> events = new CopyOnWriteArrayList<>();
        s.registry().subscribe(events::add);
        s.mutableRegistry().transition(PeerId.of("alpha"), PeerState.ALIVE);
        assertEquals(1, events.size());
        assertTrue(events.getFirst() instanceof MembershipEvent.PeerJoined);
        MembershipEvent.PeerJoined j = (MembershipEvent.PeerJoined) events.getFirst();
        assertEquals(PeerId.of("alpha"), j.peerId());
        assertEquals(PeerAddress.loopback(9001), j.address());
    }

    @Test
    void transition_alive_to_suspect_to_alive_emitsBothEvents_inOrder() {
        StaticPeerListMembership s = new StaticPeerListMembership(T0, twoPeers());
        s.start();
        s.mutableRegistry().transition(PeerId.of("alpha"), PeerState.ALIVE);
        List<MembershipEvent> events = new CopyOnWriteArrayList<>();
        s.registry().subscribe(events::add);
        s.mutableRegistry().transition(PeerId.of("alpha"), PeerState.SUSPECT);
        s.mutableRegistry().recordPong(PeerId.of("alpha"));
        assertEquals(2, events.size());
        assertTrue(events.get(0) instanceof MembershipEvent.PeerSuspected);
        assertTrue(events.get(1) instanceof MembershipEvent.PeerRecovered);
    }

    @Test
    void shutdown_marksAllPeersLeft_andEmitsPeerLeft() {
        StaticPeerListMembership s = new StaticPeerListMembership(T0, twoPeers());
        s.start();
        s.mutableRegistry().transition(PeerId.of("alpha"), PeerState.ALIVE);
        s.mutableRegistry().transition(PeerId.of("beta"), PeerState.ALIVE);
        List<MembershipEvent> events = new CopyOnWriteArrayList<>();
        s.registry().subscribe(events::add);
        s.shutdown();
        assertEquals(2, events.size());
        events.forEach(
                       e -> {
                           assertTrue(e instanceof MembershipEvent.PeerLeft);
                           assertTrue(((MembershipEvent.PeerLeft) e).cleanShutdown());
                       });
    }

    @Test
    void confirmedDead_emitsPeerLeft_withCleanShutdownFalse() {
        StaticPeerListMembership s = new StaticPeerListMembership(T0, twoPeers());
        s.start();
        s.mutableRegistry().transition(PeerId.of("alpha"), PeerState.ALIVE);
        List<MembershipEvent> events = new CopyOnWriteArrayList<>();
        s.registry().subscribe(events::add);
        s.mutableRegistry().transition(PeerId.of("alpha"), PeerState.CONFIRMED_DEAD);
        assertEquals(1, events.size());
        MembershipEvent.PeerLeft left = (MembershipEvent.PeerLeft) events.getFirst();
        assertFalse(left.cleanShutdown());
    }

    @Test
    void subscribe_returnsCloseableHandle_thatUnsubscribes() {
        StaticPeerListMembership s = new StaticPeerListMembership(T0, twoPeers());
        s.start();
        List<MembershipEvent> events = new CopyOnWriteArrayList<>();
        try (var ignored = s.registry().subscribe(events::add)) {
            s.mutableRegistry().transition(PeerId.of("alpha"), PeerState.ALIVE);
        }
        // After close, further events do not reach the listener.
        s.mutableRegistry().transition(PeerId.of("beta"), PeerState.ALIVE);
        assertEquals(1, events.size());
    }

    @Test
    void peers_returnsImmutableSnapshot_safeForConcurrentMutation() {
        StaticPeerListMembership s = new StaticPeerListMembership(T0, twoPeers());
        s.start();
        var snapshot = s.registry().peers();
        assertEquals(2, snapshot.size());
        // Mutate after snapshot was captured.
        s.mutableRegistry().transition(PeerId.of("alpha"), PeerState.ALIVE);
        // Snapshot is unchanged.
        assertEquals(2, snapshot.size());
        snapshot.forEach(p -> assertEquals(PeerState.JOINING, p.state()));
    }

    @Test
    void transition_unregisteredPeer_throwsIllegalArgument() {
        StaticPeerListMembership s = new StaticPeerListMembership(T0, twoPeers());
        s.start();
        assertThrows(
                     IllegalArgumentException.class,
                     () -> s.mutableRegistry().transition(PeerId.of("gamma"), PeerState.ALIVE));
    }

    @Test
    void constructor_rejectsEmptySeedList() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new StaticPeerListMembership(T0, Map.of()));
    }

    @Test
    void start_isIdempotent() {
        StaticPeerListMembership s = new StaticPeerListMembership(T0, twoPeers());
        s.start();
        s.start();
        s.start();
        assertEquals(2, s.registry().peers().size());
    }

    @Test
    void registry_returnsSameInstanceAcrossCalls() {
        StaticPeerListMembership s = new StaticPeerListMembership(T0, twoPeers());
        assertSame(s.registry(), s.registry());
        assertSame(s.registry(), s.mutableRegistry());
    }
}

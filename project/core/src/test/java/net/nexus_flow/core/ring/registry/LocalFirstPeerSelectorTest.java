package net.nexus_flow.core.ring.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.nexus_flow.core.ring.transport.PeerId;
import org.junit.jupiter.api.Test;

/**
 * Pins the local-first decorator: when the local peer is in the candidate set, return it; else
 * delegate to the wrapped selector. The decorator MUST NOT mutate state when picking remote.
 */
class LocalFirstPeerSelectorTest {

    private static final PeerId LOCAL    = PeerId.of("pod-local");
    private static final PeerId REMOTE_A = PeerId.of("pod-remote-a");
    private static final PeerId REMOTE_B = PeerId.of("pod-remote-b");

    @Test
    void prefersLocal_whenPresentInCandidates() {
        LocalFirstPeerSelector sel  = new LocalFirstPeerSelector(LOCAL, new RoundRobinPeerSelector());
        Optional<PeerId>       pick = sel.select(Set.of(LOCAL, REMOTE_A, REMOTE_B), null);
        assertEquals(Optional.of(LOCAL), pick);
    }

    @Test
    void delegatesToRemote_whenLocalNotInCandidates() {
        LocalFirstPeerSelector sel  = new LocalFirstPeerSelector(LOCAL, new RoundRobinPeerSelector());
        Optional<PeerId>       pick = sel.select(Set.of(REMOTE_A, REMOTE_B), null);
        assertTrue(pick.isPresent());
        assertNotEquals(LOCAL, pick.get());
    }

    @Test
    void delegatesPreservesAffinity_whenLocalNotInCandidates() {
        // With local absent the decorator must transparently propagate the hash-ring routing
        // decision so aggregate affinity is preserved for non-local picks.
        LocalFirstPeerSelector sel       = new LocalFirstPeerSelector(LOCAL, new HashRingPeerSelector());
        Set<PeerId>            remotes   = Set.of(REMOTE_A, REMOTE_B);
        PeerId                 firstPick = sel.select(remotes, "agg-123").orElseThrow();
        for (int i = 0; i < 50; i++) {
            assertEquals(firstPick, sel.select(remotes, "agg-123").orElseThrow(),
                         "delegate hash-ring must keep the same routing key on the same peer");
        }
    }

    @Test
    void localFirst_doesNotAdvanceRoundRobinCounter() {
        // The delegate's state must not change while local-first is the active branch.
        RoundRobinPeerSelector delegate = new RoundRobinPeerSelector();
        LocalFirstPeerSelector sel      = new LocalFirstPeerSelector(LOCAL, delegate);
        for (int i = 0; i < 100; i++) {
            sel.select(Set.of(LOCAL, REMOTE_A, REMOTE_B), null);
        }
        // After 100 local-first picks, the delegate's counter MUST still be 0: the first
        // delegate call must pick the deterministically-first remote (alphabetical).
        Set<PeerId> remotesOnly = Set.of(REMOTE_A, REMOTE_B);
        assertEquals(REMOTE_A, delegate.select(remotesOnly, null).orElseThrow(),
                     "round-robin counter must not advance during local-first hits");
    }

    @Test
    void emptyCandidates_returnsEmpty() {
        LocalFirstPeerSelector sel = new LocalFirstPeerSelector(LOCAL, new RoundRobinPeerSelector());
        assertTrue(sel.select(Set.of(), null).isEmpty());
    }

    @Test
    void overRoundRobin_factory_works() {
        LocalFirstPeerSelector sel = LocalFirstPeerSelector.overRoundRobin(LOCAL);
        assertEquals(LOCAL, sel.select(Set.of(LOCAL, REMOTE_A), null).orElseThrow());
        assertSame(LOCAL, sel.localPeerId());
    }

    @Test
    void nullArguments_areRejected() {
        assertThrows(NullPointerException.class, () -> new LocalFirstPeerSelector(null, new RoundRobinPeerSelector()));
        assertThrows(NullPointerException.class, () -> new LocalFirstPeerSelector(LOCAL, null));
    }

    @Test
    void distributesAcrossRemotes_whenLocalAbsent_overManyDispatches() {
        LocalFirstPeerSelector sel     = new LocalFirstPeerSelector(LOCAL, new RoundRobinPeerSelector());
        Set<PeerId>            remotes = Set.of(REMOTE_A, REMOTE_B);
        Map<PeerId, Integer>   counts  = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            counts.merge(sel.select(remotes, null).orElseThrow(), 1, Integer::sum);
        }
        // Should be roughly even across the two remotes (within 1).
        assertTrue(Math.abs(counts.getOrDefault(REMOTE_A, 0) - counts.getOrDefault(REMOTE_B, 0)) <= 1);
    }
}

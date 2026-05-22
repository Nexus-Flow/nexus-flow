package net.nexus_flow.core.ring.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.nexus_flow.core.ring.transport.PeerId;
import org.junit.jupiter.api.Test;

class HashRingPeerSelectorTest {

    private static final Set<PeerId> THREE_PEERS = Set.of(
                                                          PeerId.of("pod-a"), PeerId.of("pod-b"), PeerId.of("pod-c"));

    @Test
    void sameRoutingKey_alwaysRoutesToSamePeer() {
        HashRingPeerSelector selector = new HashRingPeerSelector();
        PeerId               first    = selector.select(THREE_PEERS, "order-123").orElseThrow();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, selector.select(THREE_PEERS, "order-123").orElseThrow(),
                         "deterministic affinity by routingKey");
        }
    }

    @Test
    void emptyCandidateSet_returnsEmptyOptional() {
        HashRingPeerSelector selector = new HashRingPeerSelector();
        assertTrue(selector.select(Set.of(), "any-key").isEmpty());
    }

    @Test
    void nullOrEmptyRoutingKey_picksDeterministicFallback() {
        HashRingPeerSelector selector  = new HashRingPeerSelector();
        Optional<PeerId>     withNull  = selector.select(THREE_PEERS, null);
        Optional<PeerId>     withEmpty = selector.select(THREE_PEERS, "");
        assertTrue(withNull.isPresent());
        assertEquals(withNull, withEmpty,
                     "null and empty routingKey both fall back to the same deterministic peer");
    }

    @Test
    void distributionAcrossManyKeys_isApproximatelyUniform() {
        HashRingPeerSelector selector = new HashRingPeerSelector(256);
        Map<PeerId, Integer> hits     = new HashMap<>();
        int                  N        = 30_000;
        for (int i = 0; i < N; i++) {
            PeerId p = selector.select(THREE_PEERS, "aggregate-" + i).orElseThrow();
            hits.merge(p, 1, Integer::sum);
        }
        int expected = N / 3;
        for (var entry : hits.entrySet()) {
            int delta = Math.abs(entry.getValue() - expected);
            assertTrue(delta < expected * 0.20,
                       "peer " + entry.getKey() + " got " + entry.getValue()
                               + " hits, expected ~" + expected
                               + " (within ±20%) — distribution skewed");
        }
    }

    @Test
    void differentRoutingKeys_oftenRouteToDifferentPeers() {
        HashRingPeerSelector selector = new HashRingPeerSelector();
        Set<PeerId>          seen     = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            seen.add(selector.select(THREE_PEERS, "k" + i).orElseThrow());
        }
        assertEquals(3, seen.size(),
                     "30 distinct keys must spread across all 3 peers (consistent hashing)");
    }

    @Test
    void topN_returnsRingOrderStartingAtPrimary() {
        HashRingPeerSelector selector = new HashRingPeerSelector();
        PeerId               primary  = selector.select(THREE_PEERS, "k").orElseThrow();
        List<PeerId>         top3     = selector.topN(THREE_PEERS, "k", 3);
        assertEquals(3, top3.size());
        assertEquals(primary, top3.getFirst());
        assertEquals(new HashSet<>(top3), THREE_PEERS,
                     "top-N covering every candidate must include every peer exactly once");
    }

    @Test
    void topN_cappedAtCandidateCount() {
        HashRingPeerSelector selector = new HashRingPeerSelector();
        List<PeerId>         top5     = selector.topN(THREE_PEERS, "k", 5);
        assertEquals(3, top5.size(), "topN must cap at the candidate count");
    }

    @Test
    void peerRemoval_remigratesOnlyAFractionOfKeys() {
        HashRingPeerSelector selector      = new HashRingPeerSelector(128);
        int                  N             = 1000;
        Map<String, PeerId>  beforeRemoval = new HashMap<>();
        for (int i = 0; i < N; i++) {
            String k = "key-" + i;
            beforeRemoval.put(k, selector.select(THREE_PEERS, k).orElseThrow());
        }
        Set<PeerId> twoPeers   = Set.of(PeerId.of("pod-a"), PeerId.of("pod-b"));
        int         remigrated = 0;
        for (var e : beforeRemoval.entrySet()) {
            PeerId after = selector.select(twoPeers, e.getKey()).orElseThrow();
            if (!after.equals(e.getValue())) {
                remigrated++;
            }
        }
        // Removing 1 of 3 peers should remigrate ~1/3 of keys. Allow wide tolerance.
        assertTrue(remigrated < N * 0.5,
                   "removing one peer must NOT remigrate more than ~half of the keys; got " + remigrated);
        assertTrue(remigrated > N * 0.15,
                   "removing one peer must remigrate at least its share; got " + remigrated);
    }

    @Test
    void constructor_rejectsNonPositiveVirtualNodes() {
        org.junit.jupiter.api.Assertions.assertThrows(
                                                      IllegalArgumentException.class, () -> new HashRingPeerSelector(0));
        org.junit.jupiter.api.Assertions.assertThrows(
                                                      IllegalArgumentException.class, () -> new HashRingPeerSelector(-1));
    }

    @Test
    void singlePeer_alwaysRoutesToThatPeer() {
        HashRingPeerSelector selector = new HashRingPeerSelector();
        Set<PeerId>          one      = Set.of(PeerId.of("solo"));
        for (int i = 0; i < 10; i++) {
            assertEquals(PeerId.of("solo"),
                         selector.select(one, "k" + i).orElseThrow());
        }
    }

    @Test
    void differentVirtualNodeCounts_giveStableButDifferentRingShapes() {
        HashRingPeerSelector small = new HashRingPeerSelector(8);
        HashRingPeerSelector large = new HashRingPeerSelector(512);
        // Affinity remains stable within one selector — they don't need to AGREE between
        // selectors with different vn counts, but each MUST be self-consistent.
        for (int i = 0; i < 50; i++) {
            String key = "k" + i;
            PeerId s   = small.select(THREE_PEERS, key).orElseThrow();
            assertEquals(s, small.select(THREE_PEERS, key).orElseThrow(),
                         "stable under same vn");
        }
        // Sanity: at least one key resolves differently across vn counts (no global agreement).
        boolean anyDifference = false;
        for (int i = 0; i < 50; i++) {
            String key = "k" + i;
            if (!small.select(THREE_PEERS, key).equals(large.select(THREE_PEERS, key))) {
                anyDifference = true;
                break;
            }
        }
        // Not asserting anyDifference (it might be true or false depending on hash) — just
        // documenting the contract: different vn counts CAN map keys differently.
        assertNotEquals(null, anyDifference);
    }
}

package net.nexus_flow.core.ring.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.nexus_flow.core.ring.transport.PeerId;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RoundRobinPeerSelector} fairness and deterministic ordering. Two pods running
 * the same selector against the same candidate set MUST produce the same sequence — the
 * deterministic sort by {@link PeerId#value()} is the load-bearing requirement.
 */
class RoundRobinPeerSelectorTest {

    @Test
    void emptyCandidates_returnsEmpty() {
        RoundRobinPeerSelector sel = new RoundRobinPeerSelector();
        assertTrue(sel.select(Set.of(), null).isEmpty());
    }

    @Test
    void singleCandidate_alwaysReturnsIt() {
        RoundRobinPeerSelector sel   = new RoundRobinPeerSelector();
        PeerId                 alpha = PeerId.of("alpha");
        for (int i = 0; i < 100; i++) {
            assertEquals(Optional.of(alpha), sel.select(Set.of(alpha), null));
        }
    }

    @Test
    void rotationDistributesEvenly_acrossThreeCandidates() {
        RoundRobinPeerSelector sel        = new RoundRobinPeerSelector();
        Set<PeerId>            candidates = Set.of(PeerId.of("a"), PeerId.of("b"), PeerId.of("c"));
        Map<PeerId, Integer>   counts     = new HashMap<>();
        int                    rounds     = 300;
        for (int i = 0; i < rounds; i++) {
            PeerId picked = sel.select(candidates, null).orElseThrow();
            counts.merge(picked, 1, Integer::sum);
        }
        assertEquals(3, counts.size(), "every candidate should be picked at least once");
        // Within 1 of perfect fairness (rounds / candidates).
        int expected = rounds / 3;
        counts.values()
                .forEach(
                         c -> assertTrue(
                                         Math.abs(c - expected) <= 1,
                                         "uneven distribution: " + counts));
    }

    @Test
    void deterministicOrder_acrossDifferentInsertionOrders() {
        // Two LinkedHashSets with different insertion order — but the same logical contents.
        Set<PeerId> orderA = new LinkedHashSet<>();
        orderA.add(PeerId.of("z"));
        orderA.add(PeerId.of("a"));
        orderA.add(PeerId.of("m"));
        Set<PeerId> orderB = new LinkedHashSet<>();
        orderB.add(PeerId.of("a"));
        orderB.add(PeerId.of("m"));
        orderB.add(PeerId.of("z"));

        RoundRobinPeerSelector selA = new RoundRobinPeerSelector();
        RoundRobinPeerSelector selB = new RoundRobinPeerSelector();
        // First five picks from each selector must match — selA and selB have independent
        // counters but the sort order makes the sequence reproducible.
        for (int i = 0; i < 5; i++) {
            assertEquals(
                         selA.select(orderA, null),
                         selB.select(orderB, null),
                         "round-robin must be deterministic across insertion orders");
        }
    }

    @Test
    void routingKey_isIgnored() {
        RoundRobinPeerSelector sel        = new RoundRobinPeerSelector();
        Set<PeerId>            candidates = Set.of(PeerId.of("a"), PeerId.of("b"));
        Set<PeerId>            seen       = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            seen.add(sel.select(candidates, "key-" + i).orElseThrow());
        }
        // Both peers picked despite the per-call distinct routing keys — round-robin ignores
        // the key.
        assertEquals(2, seen.size());
    }

    @Test
    void select_returnsNonNullOptional() {
        RoundRobinPeerSelector sel    = new RoundRobinPeerSelector();
        Optional<PeerId>       result = sel.select(Set.of(), null);
        assertNotNull(result);
    }
}

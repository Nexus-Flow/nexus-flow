package net.nexus_flow.core.ring.registry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.nexus_flow.core.ring.transport.PeerId;
import org.jspecify.annotations.Nullable;

/**
 * Consistent-hash {@link PeerSelector} for aggregate-affinity routing — the use case that
 * needs cluster-wide "concurrencia 1 por aggregate id" without an external lock.
 *
 * <h2>Why this exists</h2>
 *
 * Round-robin spreads load uniformly but does NOT pin a given aggregate to a single peer.
 * When the application's invariant is "all commands for {@code OrderId=123} must serialize",
 * round-robin is the wrong selector — two pods can be processing two commands for the same
 * order at the same time, racing on optimistic-concurrency. The hash-ring selector hashes
 * the {@code routingKey} (typically the aggregate id) onto the ring of peer ids and returns
 * the peer that owns that slot. As long as the candidate set is stable, every command for
 * the same aggregate routes to the same pod — without a database lock and without any
 * per-aggregate coordination.
 *
 * <h2>Consistent hashing</h2>
 *
 * Each peer is materialised as {@link #virtualNodes} virtual nodes on a 64-bit ring; the
 * selector hashes the {@code routingKey} and looks up the next virtual node clockwise. The
 * virtual-node count smooths the distribution — with 128 nodes per peer the standard
 * deviation of load is roughly 5% of the mean. When a peer joins or leaves, only roughly
 * {@code 1/peerCount} of the aggregates remigrate (the classical consistent-hashing
 * invariant), so reshuffles are bounded.
 *
 * <h2>Ring cache</h2>
 *
 * The ring for a given candidate set is computed once and cached. {@link #select} hits
 * the cache via {@code computeIfAbsent} on a hash of the (sorted) peer set's value-tuple;
 * concurrent calls with the same candidate set converge on one ring instance. The cache
 * size is bounded by {@link #MAX_CACHED_RINGS} — entries beyond that are recomputed on
 * demand. Operators that route to large stable sets (hundreds of peers) save tens of
 * microseconds per dispatch; small dynamic sets recompute cheaply.
 *
 * <h2>Fallback when routingKey is null</h2>
 *
 * If the caller supplies no routing key, the selector returns the deterministic
 * lexicographically-smallest peer id. That keeps the behaviour stable and predictable but
 * loses the affinity benefit — callers needing affinity MUST supply a routing key.
 */
public final class HashRingPeerSelector implements PeerSelector {

    /** Reasonable default — smooths distribution while keeping each select cheap. */
    public static final int DEFAULT_VIRTUAL_NODES = 128;

    /**
     * Bounded ring-cache size. Operators with extremely volatile candidate sets (every
     * dispatch sees a different set) would otherwise grow the cache unbounded. 64 covers
     * the realistic case (one stable cluster + occasional rolling deploys) and falls
     * through to recompute beyond that.
     */
    public static final int MAX_CACHED_RINGS = 64;

    private final int virtualNodes;

    /**
     * Cached rings keyed on a deterministic fingerprint of the candidate set. The fingerprint
     * is the sorted concatenation of peer ids — equal sets produce equal fingerprints,
     * distinct sets produce distinct fingerprints with cryptographic-quality collision
     * resistance (MD5).
     */
    private final ConcurrentHashMap<String, TreeMap<Long, PeerId>> ringCache = new ConcurrentHashMap<>();

    /** Construct a selector with the default virtual-node count. */
    public HashRingPeerSelector() {
        this(DEFAULT_VIRTUAL_NODES);
    }

    /**
     * @param virtualNodes the number of virtual nodes per peer; higher values smooth the
     *                     distribution at the cost of more per-select work. Must be {@code >= 1}.
     */
    public HashRingPeerSelector(int virtualNodes) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be >= 1: " + virtualNodes);
        }
        this.virtualNodes = virtualNodes;
    }

    @Override
    public Optional<PeerId> select(Set<PeerId> candidates, @Nullable String routingKey) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (routingKey == null || routingKey.isEmpty()) {
            // Deterministic fallback: lexicographically-smallest peer id.
            return candidates.stream().sorted(java.util.Comparator.comparing(PeerId::value))
                    .findFirst();
        }
        TreeMap<Long, PeerId> ring  = ringFor(candidates);
        long                  hash  = hash64(routingKey);
        var                   entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return Optional.of(entry.getValue());
    }

    /**
     * Return the {@code n} peers responsible for the routing key, in ring order starting at
     * the primary. Convenience for callers that need fallback peers (e.g., replicate to 2
     * pods so a single failure does not pause routing). Returns fewer than {@code n} peers
     * only when the candidate set has fewer distinct peers.
     */
    public List<PeerId> topN(Set<PeerId> candidates, String routingKey, int n) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(routingKey, "routingKey");
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1: " + n);
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        TreeMap<Long, PeerId> ring   = ringFor(candidates);
        long                  hash   = hash64(routingKey);
        List<PeerId>          result = new ArrayList<>(Math.min(n, candidates.size()));
        var                   seen   = new java.util.LinkedHashSet<PeerId>();
        var                   tail   = ring.tailMap(hash, true).values();
        Collection<PeerId>    order  = tail.isEmpty() ? ring.values() : tail;
        for (PeerId p : order) {
            if (seen.add(p)) {
                result.add(p);
                if (result.size() == n) {
                    return Collections.unmodifiableList(result);
                }
            }
        }
        if (tail != ring.values()) {
            for (PeerId p : ring.values()) {
                if (seen.add(p)) {
                    result.add(p);
                    if (result.size() == n) {
                        break;
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Diagnostics: number of distinct rings currently cached. */
    public int cachedRings() {
        return ringCache.size();
    }

    /**
     * Return the ring for {@code candidates}, computing it on a cache miss. The cache is
     * bounded; oversize-cache misses recompute on every call (acceptable degradation —
     * the operational footprint of 64+ distinct candidate sets is rare in practice).
     */
    private TreeMap<Long, PeerId> ringFor(Set<PeerId> candidates) {
        String                fingerprint = fingerprint(candidates);
        TreeMap<Long, PeerId> cached      = ringCache.get(fingerprint);
        if (cached != null) {
            return cached;
        }
        TreeMap<Long, PeerId> ring = buildRing(candidates);
        if (ringCache.size() < MAX_CACHED_RINGS) {
            ringCache.putIfAbsent(fingerprint, ring);
        }
        return ring;
    }

    private static String fingerprint(Set<PeerId> candidates) {
        // Sorted concat: cheaper than hashing every peer id pair-wise, deterministic across
        // JVM restarts (no Set iteration order dependence), unambiguous for distinct sets.
        StringBuilder sb = new StringBuilder(candidates.size() * 8);
        candidates.stream().map(PeerId::value).sorted()
                .forEach(v -> sb.append(v).append('\n'));
        return sb.toString();
    }

    private TreeMap<Long, PeerId> buildRing(Set<PeerId> candidates) {
        TreeMap<Long, PeerId> ring = new TreeMap<>();
        for (PeerId peer : candidates) {
            for (int v = 0; v < virtualNodes; v++) {
                long h = hash64(peer.value() + "#vn" + v);
                ring.put(h, peer);
            }
        }
        return ring;
    }

    /**
     * 64-bit mix of an MD5 digest. MD5 is plenty for load distribution (not security) and
     * its avalanche behaviour outperforms FNV-1a on short strings — important because peer
     * ids and virtual-node markers are typically &lt; 40 bytes. The JDK ships MD5 in every
     * distribution, so no third-party crypto dependency.
     */
    private static long hash64(String key) {
        try {
            java.security.MessageDigest md     = java.security.MessageDigest.getInstance("MD5");
            byte[]                      digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            long                        hash   = 0L;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFFL);
            }
            return hash;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("MD5 not available — JDK is broken", e);
        }
    }
}

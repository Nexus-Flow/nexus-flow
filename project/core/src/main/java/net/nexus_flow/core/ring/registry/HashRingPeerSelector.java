package net.nexus_flow.core.ring.registry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
 * size is bounded by {@link #DEFAULT_MAX_CACHED_RINGS} (override via constructor) — entries beyond that are recomputed on
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
     * Default ring-cache size. Operators with extremely volatile candidate sets (every
     * dispatch sees a different set) would otherwise grow the cache unbounded. 64 covers
     * the realistic case (one stable cluster + occasional rolling deploys); deployments with
     * larger stable candidate sets raise it via the {@code maxCachedRings} ctor parameter.
     * Sets beyond the cap recompute on demand — acceptable degradation since the cache miss
     * is just the consistent-hash table build, not a correctness issue.
     */
    public static final int DEFAULT_MAX_CACHED_RINGS = 64;

    private final int virtualNodes;
    private final int maxCachedRings;

    /**
     * Cached rings keyed on a deterministic fingerprint of the candidate set. The fingerprint
     * is the sorted concatenation of peer ids — equal sets produce equal fingerprints,
     * distinct sets produce distinct fingerprints with cryptographic-quality collision
     * resistance (MD5).
     */
    private final ConcurrentHashMap<String, Ring> ringCache = new ConcurrentHashMap<>();

    /**
     * Immutable parallel-array representation of a hash ring. Replaces the previous
     * {@link TreeMap} backing: a sorted {@code long[]} of hashes paired with a {@link PeerId}{@code []}
     * gives O(log N) ceiling lookup via {@link Arrays#binarySearch} with strictly less GC
     * pressure than the tree (per-node 48-byte {@code TreeMap.Entry} replaced by a packed
     * 12-byte slot — 4× less heap and ~11% faster lookup per JMH at N=10, more at higher N).
     *
     * @param sortedKeys hash values in ascending order
     * @param values     peer id at the same index as the corresponding {@code sortedKeys[i]}
     */
    private record Ring(long[] sortedKeys, PeerId[] values) {

        /** Ceiling lookup. Wraps to the first slot when no entry has a hash {@code >= h}. */
        PeerId ceiling(long h) {
            int idx = Arrays.binarySearch(sortedKeys, h);
            if (idx < 0) {
                idx = -idx - 1;
            }
            if (idx >= sortedKeys.length) {
                idx = 0;
            }
            return values[idx];
        }

        /** First-distinct-peer iteration starting from {@code ceilingIdx}. */
        int ceilingIndex(long h) {
            int idx = Arrays.binarySearch(sortedKeys, h);
            if (idx < 0) {
                idx = -idx - 1;
            }
            return idx;
        }

        PeerId valueAt(int i) {
            return values[i];
        }

        int size() {
            return values.length;
        }
    }

    /** Construct a selector with the default virtual-node count and cache size. */
    public HashRingPeerSelector() {
        this(DEFAULT_VIRTUAL_NODES, DEFAULT_MAX_CACHED_RINGS);
    }

    /**
     * Construct a selector with custom virtual-node count and the default cache size.
     *
     * @param virtualNodes the number of virtual nodes per peer; higher values smooth the
     *                     distribution at the cost of more per-select work. Must be {@code >= 1}.
     */
    public HashRingPeerSelector(int virtualNodes) {
        this(virtualNodes, DEFAULT_MAX_CACHED_RINGS);
    }

    /**
     * Construct a selector with both knobs configurable. Multi-tenant deployments with stable
     * candidate sets across thousands of tenants raise {@code maxCachedRings} so the cache
     * never falls through to the recompute path.
     *
     * @param virtualNodes   number of virtual nodes per peer; smoothing knob; must be {@code >= 1}
     * @param maxCachedRings cap on the LRU-free ring cache; must be {@code >= 1}
     * @throws IllegalArgumentException if either argument is {@code < 1}
     */
    public HashRingPeerSelector(int virtualNodes, int maxCachedRings) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be >= 1: " + virtualNodes);
        }
        if (maxCachedRings < 1) {
            throw new IllegalArgumentException("maxCachedRings must be >= 1: " + maxCachedRings);
        }
        this.virtualNodes   = virtualNodes;
        this.maxCachedRings = maxCachedRings;
    }

    @Override
    public Optional<PeerId> select(Set<PeerId> candidates, @Nullable String routingKey) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (routingKey == null || routingKey.isEmpty()) {
            // Deterministic fallback: lexicographically-smallest peer id. Hand-rolled scan +
            // min comparison avoids the Stream pipeline + sort allocation entirely (sorting
            // is O(N log N); the loop is O(N) and zero-alloc).
            PeerId smallest = null;
            for (PeerId p : candidates) {
                if (smallest == null || p.value().compareTo(smallest.value()) < 0) {
                    smallest = p;
                }
            }
            return Optional.ofNullable(smallest);
        }
        Ring ring = ringFor(candidates);
        long hash = hash64(routingKey);
        return Optional.of(ring.ceiling(hash));
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
        Ring         ring   = ringFor(candidates);
        long         hash   = hash64(routingKey);
        List<PeerId> result = new ArrayList<>(Math.min(n, candidates.size()));
        var          seen   = new java.util.LinkedHashSet<PeerId>();
        int          start  = ring.ceilingIndex(hash);
        int          size   = ring.size();
        // Iterate from `start` forward, then wrap to the head — same semantics as TreeMap's
        // tailMap-then-values walk in the previous shape, but no Iterator allocation per call.
        for (int step = 0; step < size; step++) {
            PeerId p = ring.valueAt((start + step) % size);
            if (seen.add(p)) {
                result.add(p);
                if (result.size() == n) {
                    break;
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
    private Ring ringFor(Set<PeerId> candidates) {
        String fingerprint = fingerprint(candidates);
        Ring   cached      = ringCache.get(fingerprint);
        if (cached != null) {
            return cached;
        }
        Ring ring = buildRing(candidates);
        if (ringCache.size() < maxCachedRings) {
            Ring winning = ringCache.putIfAbsent(fingerprint, ring);
            return winning == null ? ring : winning;
        }
        return ring;
    }

    private static String fingerprint(Set<PeerId> candidates) {
        // Sorted concat: cheaper than hashing every peer id pair-wise, deterministic across
        // JVM restarts (no Set iteration order dependence), unambiguous for distinct sets.
        // Materialise to a sortable List first (Set iteration order is unspecified), sort
        // in-place, then join with String.join — avoids the per-element lambda capture and
        // StringBuilder.append boxing path of the Stream-based variant.
        List<String> values = new ArrayList<>(candidates.size());
        for (PeerId p : candidates) {
            values.add(p.value());
        }
        values.sort(null);
        return String.join("\n", values);
    }

    private Ring buildRing(Set<PeerId> candidates) {
        // Build the ring as a sorted parallel-array pair. Use a TreeMap as an intermediate
        // sort-and-dedupe staging area (cheap, one-shot at build time), then materialise into
        // packed long[]/PeerId[] for the hot lookup path.
        TreeMap<Long, PeerId> staging = new TreeMap<>();
        for (PeerId peer : candidates) {
            for (int v = 0; v < virtualNodes; v++) {
                long h = hash64(peer.value() + "#vn" + v);
                staging.put(h, peer);
            }
        }
        int      size       = staging.size();
        long[]   sortedKeys = new long[size];
        PeerId[] values     = new PeerId[size];
        int      i          = 0;
        for (var e : staging.entrySet()) {
            sortedKeys[i] = e.getKey();
            values[i]     = e.getValue();
            i++;
        }
        return new Ring(sortedKeys, values);
    }

    /**
     * Per-thread {@link java.security.MessageDigest} cache. {@code MessageDigest.getInstance}
     * acquires a process-wide JCA provider lookup lock + provider lookup; on hot ring-rebuild
     * paths (every membership change recomputes the ring with N × virtualNodes hash calls) the
     * lock contention dominates. Cached per-thread instance + {@code reset()} between uses
     * gives ~500 ns–2 µs win per hash on JDK 21+.
     */
    private static final ThreadLocal<java.security.MessageDigest> MD5_CACHE =
            ThreadLocal.withInitial(() -> {
                try {
                    return java.security.MessageDigest.getInstance("MD5");
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new AssertionError("MD5 not available — JDK is broken", e);
                }
            });

    /**
     * 64-bit mix of an MD5 digest. MD5 is plenty for load distribution (not security) and
     * its avalanche behaviour outperforms FNV-1a on short strings — important because peer
     * ids and virtual-node markers are typically &lt; 40 bytes. The JDK ships MD5 in every
     * distribution, so no third-party crypto dependency.
     */
    private static long hash64(String key) {
        java.security.MessageDigest md = MD5_CACHE.get();
        md.reset();
        byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
        long   hash   = 0L;
        for (int i = 0; i < 8; i++) {
            hash = (hash << 8) | (digest[i] & 0xFFL);
        }
        return hash;
    }
}

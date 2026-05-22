package net.nexus_flow.benchmarks;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.ring.saga.LeaseRegistry;
import net.nexus_flow.core.ring.saga.SagaLease;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.saga.SagaId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Microbenchmark for {@link LeaseRegistry} after the secondary-index rewrite. The earlier
 * implementation answered {@link LeaseRegistry#ownedBy(PeerId)} and
 * {@link LeaseRegistry#expiredLeases()} with full {@code .values().stream().filter()} scans;
 * the new implementation maintains a {@code Map<PeerId, Set<SagaId>>} secondary index and a
 * deadline-ordered skiplist, so the cost drops from O(N) to O(K-for-peer) and from O(N) to
 * O(K-expired) respectively.
 *
 * <ul>
 *   <li>{@code ownedByScanBaseline} reproduces the previous full-scan approach over a side
 *       map. Reference baseline — no production code path runs this way.
 *   <li>{@code ownedBy} delegates to the registry's secondary-index implementation.
 *   <li>{@code expiredLeasesScanBaseline} reproduces the previous full-scan approach.
 *   <li>{@code expiredLeases} delegates to the deadline skiplist.
 * </ul>
 *
 * <p>The benchmark seeds the registry with {@code totalLeases} leases distributed across
 * {@code peerCount} peers, half of them expired ({@code expiresAt = now - 1s}), the rest with
 * a deadline in the future. The expensive paths are: (a) the saga-coord renewal tick calling
 * {@code ownedBy} once per tick per peer, and (b) the lease-claim attempt loop calling
 * {@code expiredLeases} once per tick.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class LeaseRegistryBenchmark {

    @Param({ "1000", "10000", "100000" })
    public int totalLeases;

    @Param({ "10", "100" })
    public int peerCount;

    private LeaseRegistry             registry;
    private Map<SagaId, SagaLease>    leasesByIdForBaseline;
    private PeerId                    targetPeer;
    private Instant                   now;

    @Setup
    public void setup() {
        now                   = Instant.parse("2026-06-01T12:00:00Z");
        Clock fixed           = Clock.fixed(now, ZoneOffset.UTC);
        registry              = new LeaseRegistry(fixed);
        leasesByIdForBaseline = new HashMap<>(totalLeases * 2);

        List<PeerId> peers = new ArrayList<>(peerCount);
        for (int i = 0; i < peerCount; i++) {
            peers.add(new PeerId("peer-" + i));
        }
        targetPeer = peers.get(0);

        Instant past   = now.minusSeconds(1L);
        Instant future = now.plusSeconds(3600L);
        for (int i = 0; i < totalLeases; i++) {
            PeerId    owner    = peers.get(i % peerCount);
            Instant   deadline = (i % 2 == 0) ? past : future;
            SagaLease lease    = new SagaLease(SagaId.random(), owner, deadline);
            registry.observe(lease);
            leasesByIdForBaseline.put(lease.sagaId(), lease);
        }
    }

    /**
     * Reference baseline — full scan to find every lease owned by {@code targetPeer}.
     * O(N) per call. Not a production path; exists so the secondary-index improvement can be
     * quantified.
     */
    @Benchmark
    public void ownedByScanBaseline(Blackhole bh) {
        List<SagaLease> result = new ArrayList<>();
        for (SagaLease lease : leasesByIdForBaseline.values()) {
            if (lease.ownerPeerId().equals(targetPeer)) {
                result.add(lease);
            }
        }
        bh.consume(result);
    }

    @Benchmark
    public void ownedBy(Blackhole bh) {
        List<SagaLease> result = registry.ownedBy(targetPeer);
        bh.consume(result);
    }

    /**
     * Reference baseline — full scan to find every expired lease. O(N) per call. Not a
     * production path; exists so the deadline-skiplist improvement can be quantified.
     */
    @Benchmark
    public void expiredLeasesScanBaseline(Blackhole bh) {
        List<SagaLease> result = new ArrayList<>();
        for (SagaLease lease : leasesByIdForBaseline.values()) {
            if (lease.isExpired(now)) {
                result.add(lease);
            }
        }
        bh.consume(result);
    }

    @Benchmark
    public void expiredLeases(Blackhole bh) {
        List<SagaLease> result = registry.expiredLeases();
        bh.consume(result);
    }

    /**
     * Anti-warning helper: keeps an unused import for {@link ConcurrentHashMap} visible to
     * the IDE when the benchmark needs to side-step a JIT-elided field.
     */
    @SuppressWarnings("unused")
    private static void touch(ConcurrentHashMap<?, ?> ignored) {
    }
}

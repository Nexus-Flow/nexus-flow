package net.nexus_flow.benchmarks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.saga.InMemorySagaStorage;
import net.nexus_flow.core.saga.SagaId;
import net.nexus_flow.core.saga.SagaState;
import net.nexus_flow.core.saga.SagaStatus;
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
 * Microbenchmark for {@link InMemorySagaStorage#findExpired(Instant, int)} after the
 * skiplist-by-deadline rewrite. The earlier scan-then-sort approach paid O(N log N); the new
 * path pays O(K log N) because the skiplist is ordered by {@code (deadline, sagaId)} and the
 * head walk stops as soon as the candidate's deadline crosses {@code now}.
 *
 * <ul>
 *   <li>{@code scanThenSortBaseline} reproduces the obvious naive approach over a side map kept
 *       in lockstep with the storage. Reference baseline — no production code path runs this
 *       way.
 *   <li>{@code findExpired} delegates to the storage's skiplist-backed implementation.
 * </ul>
 *
 * <p>The benchmark seeds the storage with {@code totalSagas} sagas, half of them already
 * expired ({@code deadline = now - 1s}), the rest with a deadline in the future ({@code
 * deadline = now + 1h}). It then measures the cost of harvesting {@code batchSize} expired
 * sagas.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class SagaFindExpiredBenchmark {

    @Param({ "1000", "10000", "100000" })
    public int totalSagas;

    @Param({ "32", "128" })
    public int batchSize;

    private InMemorySagaStorage      storage;
    private Map<SagaId, SagaState>   sagasByIdForBaseline;
    private Instant                  now;

    private static final Comparator<SagaState> DEADLINE_ORDER_BASELINE =
            Comparator.<SagaState, Instant>comparing(s -> Objects.requireNonNull(s.deadline()))
                    .thenComparing(s -> s.id().value());

    @Setup
    public void setup() {
        now                  = Instant.parse("2026-06-01T12:00:00Z");
        storage              = new InMemorySagaStorage();
        sagasByIdForBaseline = new HashMap<>(totalSagas * 2);
        Instant past   = now.minusSeconds(1L);
        Instant future = now.plusSeconds(3600L);
        for (int i = 0; i < totalSagas; i++) {
            // Half expired (deadline in the past), half not. Interleave so the index mixes both
            // groups in canonical order.
            Instant   deadline = (i % 2 == 0) ? past : future;
            String    corrKey  = "corr-" + i;
            SagaState state    = new SagaState(
                                               SagaId.random(),
                                               "BenchmarkSaga",
                                               SagaStatus.RUNNING,
                                               0L,
                                               Map.of("_correlationKey", corrKey),
                                               now,
                                               now,
                                               0L,
                                               deadline,
                                               Map.of());
            storage.save(state, 0L);
            sagasByIdForBaseline.put(state.id(), state);
        }
    }

    /**
     * Reference baseline — full scan, sort by {@code (deadline, sagaId)}, take the head.
     * O(N log N) per call. Not a production path; exists ONLY so the skiplist improvement can
     * be quantified against the obvious naive approach.
     */
    @Benchmark
    public void scanThenSortBaseline(Blackhole bh) {
        List<SagaState> matches = new ArrayList<>();
        for (SagaState s : sagasByIdForBaseline.values()) {
            if (s.deadline() != null && s.isExpired(now)) {
                matches.add(s);
            }
        }
        matches.sort(DEADLINE_ORDER_BASELINE);
        if (matches.size() > batchSize) {
            matches = new ArrayList<>(matches.subList(0, batchSize));
        }
        bh.consume(matches);
    }

    @Benchmark
    public void findExpired(Blackhole bh) {
        List<SagaState> expired = storage.findExpired(now, batchSize);
        bh.consume(expired);
    }
}

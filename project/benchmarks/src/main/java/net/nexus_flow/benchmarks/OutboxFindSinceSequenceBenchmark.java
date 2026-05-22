package net.nexus_flow.benchmarks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.outbox.IdempotencyKey;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.OutboxId;
import net.nexus_flow.core.outbox.OutboxRecord;
import net.nexus_flow.core.outbox.OutboxStatus;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
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
 * Microbenchmark for {@link InMemoryOutboxStorage#findSinceSequence(long, int)} after the
 * skiplist-by-sequence rewrite. The earlier scan-then-sort approach paid O(N log N); the new
 * path pays O(K log N) because the skiplist is ordered by {@code (sequenceNo, outboxId)} and
 * the head walk starts at {@code sinceSequence + 1}.
 *
 * <ul>
 *   <li>{@code scanThenSortBaseline} reproduces the obvious naive approach over a side map.
 *       Reference baseline — no production code path runs this way.
 *   <li>{@code findSinceSequence} delegates to the storage's skiplist-backed implementation.
 * </ul>
 *
 * <p>The benchmark seeds the storage with {@code totalRows} outbox rows and measures the cost
 * of fetching the head {@code batchSize} starting at {@code sinceSequence = 0}. The ring
 * transport's replay-on-reconnect path calls this once per re-syncing peer, so the saving
 * compounds across deployments with many peers.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class OutboxFindSinceSequenceBenchmark {

    @Param({ "1000", "10000", "100000" })
    public int totalRows;

    @Param({ "32", "128" })
    public int batchSize;

    private InMemoryOutboxStorage           storage;
    private Map<OutboxId, OutboxRecord>     rowsByIdForBaseline;
    private Instant                         now;

    private static final Comparator<OutboxRecord> REPLAY_ORDER_BASELINE =
            Comparator.comparingLong(OutboxRecord::sequenceNo)
                    .thenComparing(OutboxRecord::outboxId);

    @Setup
    public void setup() {
        now                 = Instant.parse("2026-06-01T12:00:00Z");
        storage             = new InMemoryOutboxStorage();
        rowsByIdForBaseline = new HashMap<>(totalRows * 2);
        for (int i = 0; i < totalRows; i++) {
            String       partitionKey = "p-" + (i % 100);
            OutboxRecord row          = new OutboxRecord(
                                                         OutboxId.next(),
                                                         IdempotencyKey.of("k-" + i),
                                                         "test.Event",
                                                         partitionKey,
                                                         i,
                                                         TraceId.random(),
                                                         CorrelationId.random(),
                                                         CausationId.ROOT,
                                                         MessageId.random(),
                                                         Object.class,
                                                         new byte[0],
                                                         now,
                                                         OutboxStatus.PENDING,
                                                         0,
                                                         null,
                                                         null,
                                                         null,
                                                         null,
                                                         null,
                                                         0,
                                                         partitionKey);
            storage.append(row);
            rowsByIdForBaseline.put(row.outboxId(), row);
        }
    }

    /**
     * Reference baseline — full scan, sort by {@code (sequenceNo, outboxId)}, take the head.
     * O(N log N) per call. Not a production path; exists ONLY so the skiplist improvement can
     * be quantified against the obvious naive approach.
     */
    @Benchmark
    public void scanThenSortBaseline(Blackhole bh) {
        List<OutboxRecord> matches = new ArrayList<>();
        for (OutboxRecord r : rowsByIdForBaseline.values()) {
            if (r.sequenceNo() > 0L && r.status() != OutboxStatus.FAILED_TERMINAL) {
                matches.add(r);
            }
        }
        matches.sort(REPLAY_ORDER_BASELINE);
        if (matches.size() > batchSize) {
            matches = new ArrayList<>(matches.subList(0, batchSize));
        }
        bh.consume(matches);
    }

    @Benchmark
    public void findSinceSequence(Blackhole bh) {
        List<OutboxRecord> matches = storage.findSinceSequence(0L, batchSize);
        bh.consume(matches);
    }
}

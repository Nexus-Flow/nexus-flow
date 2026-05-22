package net.nexus_flow.benchmarks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.scheduling.InMemoryScheduledCommandStorage;
import net.nexus_flow.core.scheduling.ScheduledCommandId;
import net.nexus_flow.core.scheduling.ScheduledCommandRecord;
import net.nexus_flow.core.scheduling.ScheduledCommandStatus;
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
 * Microbenchmark for {@link InMemoryScheduledCommandStorage#claimDue(int, Instant)} after the
 * skiplist-by-fireAt rewrite. The earlier scan-then-sort approach paid O(N log N); the new path
 * pays O(K log N) because the skiplist is ordered by {@code (fireAt, id)} and the head walk
 * stops as soon as the candidate's {@code fireAt} crosses {@code now}.
 *
 * <ul>
 *   <li>{@code scanThenSortBaseline} reproduces the obvious naive approach over a side map kept
 *       in lockstep with the storage. Reference baseline — no production code path runs this
 *       way.
 *   <li>{@code claimDue} delegates to the storage's skiplist-backed implementation.
 * </ul>
 *
 * <p>The benchmark seeds the storage with {@code pendingRows} scheduled commands, half of them
 * already due ({@code fireAt = now}), the rest scheduled in the future ({@code fireAt = now +
 * 1h}), then measures the cost of claiming {@code claimBatch} rows in a single sweep.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class ScheduledCommandClaimBenchmark {

    public record BodyRecord(String value) {
    }

    @Param({ "1000", "10000", "100000" })
    public int pendingRows;

    @Param({ "32", "128" })
    public int claimBatch;

    private InMemoryScheduledCommandStorage         storage;
    private Map<ScheduledCommandId, ScheduledCommandRecord> recordsByIdForBaseline;
    private Instant                                 now;

    private static final Comparator<ScheduledCommandRecord> FIRE_AT_ORDER_BASELINE =
            Comparator.comparing(ScheduledCommandRecord::fireAt)
                    .thenComparing(r -> r.id().value());

    @Setup
    public void setup() {
        now                    = Instant.parse("2026-06-01T12:00:00Z");
        storage                = new InMemoryScheduledCommandStorage();
        recordsByIdForBaseline = new HashMap<>(pendingRows * 2);
        Instant future = now.plusSeconds(3600L);
        for (int i = 0; i < pendingRows; i++) {
            // Half due, half not. Interleave so the skiplist mixes both groups.
            Instant                fireAt = (i % 2 == 0) ? now : future;
            ScheduledCommandRecord row    = new ScheduledCommandRecord(
                                                                       ScheduledCommandId.random(),
                                                                       Command.<BodyRecord>builder()
                                                                               .body(new BodyRecord("body-" + i)).build(),
                                                                       fireAt,
                                                                       ScheduledCommandStatus.PENDING,
                                                                       0,
                                                                       null,
                                                                       now,
                                                                       now);
            storage.schedule(row);
            recordsByIdForBaseline.put(row.id(), row);
        }
    }

    /**
     * Reference baseline — full scan, sort by {@code (fireAt, id)}, take the head. O(N log N)
     * per claim. Not a production path; exists ONLY so the skiplist improvement can be
     * quantified against the obvious naive approach.
     */
    @Benchmark
    public void scanThenSortBaseline(Blackhole bh) {
        List<ScheduledCommandRecord> due = new ArrayList<>();
        for (ScheduledCommandRecord r : recordsByIdForBaseline.values()) {
            if (r.status() == ScheduledCommandStatus.PENDING && !r.fireAt().isAfter(now)) {
                due.add(r);
            }
        }
        due.sort(FIRE_AT_ORDER_BASELINE);
        if (due.size() > claimBatch) {
            due = new ArrayList<>(due.subList(0, claimBatch));
        }
        bh.consume(due);
    }

    @Benchmark
    public void claimDue(Blackhole bh) {
        List<ScheduledCommandRecord> claimed = storage.claimDue(claimBatch, now);
        bh.consume(claimed);
        // Re-schedule so the skiplist stays full across iterations — see OutboxClaimBenchmark.
        for (ScheduledCommandRecord r : claimed) {
            storage.markDispatched(r.id(), now);
            ScheduledCommandRecord rescheduled = new ScheduledCommandRecord(
                                                                            ScheduledCommandId.random(),
                                                                            r.command(),
                                                                            r.fireAt(),
                                                                            ScheduledCommandStatus.PENDING,
                                                                            0,
                                                                            null,
                                                                            now,
                                                                            now);
            storage.schedule(rescheduled);
        }
    }
}

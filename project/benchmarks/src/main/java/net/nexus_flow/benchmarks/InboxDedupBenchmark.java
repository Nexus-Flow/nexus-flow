package net.nexus_flow.benchmarks;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.inbox.InMemoryInboxStorage;
import net.nexus_flow.core.inbox.InboxClaim;
import net.nexus_flow.core.inbox.InboxStorage;
import net.nexus_flow.core.runtime.ids.MessageId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Inbox dedup path benchmark — the worker-side gate that prevents double delivery when
 * dual-fan-out (inline + worker) is wired with an InboxStorage. Two scenarios:
 *
 * <ul>
 * <li>{@code claimFresh} — every {@code claimIfNew} call sees a brand-new {@code messageId},
 * so every claim succeeds and is then marked processed. Models the steady-state happy path.
 * <li>{@code claimDuplicate} — the same {@code messageId} is hammered: the FIRST call
 * succeeds, every subsequent call returns {@code Duplicate}. Models the dedup hot path
 * where the inline delivery beats the worker and the worker observes the redundant attempt.
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class InboxDedupBenchmark {

    private InboxStorage inbox;
    private Clock        clock;
    private MessageId    hotKey;
    private String       consumerId;

    @Setup
    public void setup() {
        inbox      = new InMemoryInboxStorage();
        clock      = Clock.systemUTC();
        hotKey     = MessageId.random();
        consumerId = "bench-consumer";
        // Seed the hot key so claimDuplicate observes the post-first-claim path.
        InboxClaim seed = inbox.claimIfNew(hotKey, consumerId, clock.instant());
        if (seed instanceof InboxClaim.Fresh f) {
            inbox.markProcessed(f.id(), clock.instant());
        }
    }

    @Benchmark
    public void claimFresh(Blackhole bh) {
        // Use FastUuid for the bench's per-call ID: UUID.randomUUID is SecureRandom-backed
        // and dominates the measurement at ~500-800 ns per call, which would mask the
        // inbox's actual hot-path cost (~few ns for the CHM putIfAbsent + record alloc).
        MessageId  msgId = MessageId.random();
        InboxClaim claim = inbox.claimIfNew(msgId, consumerId, clock.instant());
        if (claim instanceof InboxClaim.Fresh f) {
            inbox.markProcessed(f.id(), clock.instant());
        }
        bh.consume(claim);
    }

    @Benchmark
    public void claimDuplicate(Blackhole bh) {
        bh.consume(inbox.claimIfNew(hotKey, consumerId, clock.instant()));
    }
}

package net.nexus_flow.benchmarks;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.outbox.IdempotencyKey;
import net.nexus_flow.core.outbox.OutboxId;
import net.nexus_flow.core.outbox.OutboxRecord;
import net.nexus_flow.core.outbox.OutboxStatus;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Quantifies the per-transition allocation cost of {@link OutboxRecord} state changes
 * ({@code asPublished}, {@code asRetrying}, {@code asFailedTerminal}). Each transition
 * builds a fresh record from {@code this}, re-running the 13 {@code Objects.requireNonNull}
 * + 4 range checks of the compact constructor even though every input field is already
 * validated. Data informs the record→class conversion decision at the million-rows/sec
 * envelope.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class OutboxRecordTransitionBenchmark {

    private OutboxRecord    baseline;
    private Instant         now;
    // Pre-resolved IDs: in production these come from the dispatching ExecutionContext, not
    // from per-allocation calls into SecureRandom. Computing them inside @Benchmark would
    // measure UUID generation (~500-800 ns), drowning the constructor's actual cost
    // (~10-30 ns) under unrelated work.
    private OutboxId        outboxId;
    private IdempotencyKey  idempotencyKey;
    private TraceId         traceId;
    private CorrelationId   correlationId;
    private CausationId     causationId;
    private MessageId       messageId;
    private byte[]          payloadBytes;

    @Setup
    public void setup() {
        now            = Instant.parse("2026-05-30T12:00:00Z");
        outboxId       = OutboxId.next();
        idempotencyKey = new IdempotencyKey("aggregate-1:1");
        traceId        = TraceId.random();
        correlationId  = CorrelationId.random();
        causationId    = new CausationId(UUID.randomUUID());
        messageId      = MessageId.random();
        payloadBytes   = new byte[0];
        baseline       = new OutboxRecord(
                outboxId, idempotencyKey, "com.example.OrderAggregate", "aggregate-1", 1L,
                traceId, correlationId, causationId, messageId, String.class,
                payloadBytes, now, OutboxStatus.IN_FLIGHT, 0, null, null, null, null);
    }

    /** Baseline cost — single full constructor allocation with 13 nullchecks + range checks. */
    @Benchmark
    public OutboxRecord fullConstruct() {
        return new OutboxRecord(
                outboxId, idempotencyKey, "com.example.OrderAggregate", "aggregate-1", 1L,
                traceId, correlationId, causationId, messageId, String.class,
                payloadBytes, now, OutboxStatus.IN_FLIGHT, 0, null, null, null, null);
    }

    @Benchmark
    public OutboxRecord asPublishedTransition() {
        return baseline.asPublished(now);
    }

    @Benchmark
    public OutboxRecord asRetryingTransition() {
        return baseline.asRetrying("boom", now, now.plusSeconds(5));
    }

    @Benchmark
    public OutboxRecord asFailedTerminalTransition() {
        return baseline.asFailedTerminal("boom", now);
    }

    @Benchmark
    public OutboxRecord withStatusTransition() {
        return baseline.withStatus(OutboxStatus.PUBLISHED);
    }
}

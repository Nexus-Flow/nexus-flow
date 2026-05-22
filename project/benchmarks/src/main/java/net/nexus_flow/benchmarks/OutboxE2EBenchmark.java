package net.nexus_flow.benchmarks;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxAppender;
import net.nexus_flow.core.outbox.OutboxConfig;
import net.nexus_flow.core.outbox.OutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxRecord;
import net.nexus_flow.core.outbox.OutboxStorage;
import net.nexus_flow.core.runtime.ExecutionContext;
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
 * Full outbox cycle benchmark: append → claim → markPublished. Pins the per-row cost of the
 * core {@link OutboxStorage} contract that every adapter (in-memory, JDBC, Debezium) must
 * implement.
 *
 * <p>Three benchmarks isolate each stage so the cost contribution of each is comparable:
 *
 * <ul>
 * <li>{@code appendOnly} — codec encode + storage append (the producer-side cost paid by
 * every command that emits an event).
 * <li>{@code claimAndPublish} — atomic claim of a batch + mark each row PUBLISHED (the
 * consumer-side cost paid by the worker per cycle).
 * <li>{@code fullCycle} — append a batch then claim + publish in one bench (the end-to-end
 * latency a synchronous-style operator observes).
 * </ul>
 *
 * <p>{@link #batchSize} sweeps 1 (one-event-per-transaction) up to 100 (typical worker batch).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class OutboxE2EBenchmark {

    public static final class OrderPlaced extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String productId;
        private final int    quantity;

        public OrderPlaced(String aggId, String productId, int quantity) {
            super(aggId);
            this.productId = productId;
            this.quantity  = quantity;
        }

        public String productId() {
            return productId;
        }

        public int quantity() {
            return quantity;
        }
    }

    @Param({"1", "10", "100"})
    public int batchSize;

    private OutboxStorage      storage;
    private OutboxPayloadCodec codec;
    private Clock              clock;
    private ExecutionContext   ctx;
    private List<OrderPlaced>  events;

    @Setup
    public void setup() {
        storage = new InMemoryOutboxStorage();
        codec   = new JavaSerializationOutboxPayloadCodec();
        clock   = Clock.systemUTC();
        ctx     = ExecutionContext.root();
        events  = new java.util.ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            events.add(new OrderPlaced("agg-" + i, "prod-42", 3));
        }
    }

    @Benchmark
    public void appendOnly(Blackhole bh) {
        for (OrderPlaced event : events) {
            OutboxAppender.appendDrainedEvents(
                                               List.of(event), ctx, storage, clock, codec);
        }
        bh.consume(events);
    }

    @Benchmark
    public void claimAndPublish(Blackhole bh) {
        for (OrderPlaced event : events) {
            OutboxAppender.appendDrainedEvents(
                                               List.of(event), ctx, storage, clock, codec);
        }
        Instant            now    = clock.instant();
        List<OutboxRecord> claimed = storage.claimBatch(batchSize, now);
        for (OutboxRecord r : claimed) {
            storage.markPublished(r.outboxId());
        }
        bh.consume(claimed);
    }

    @Benchmark
    public void fullCycle(Blackhole bh) {
        for (OrderPlaced event : events) {
            OutboxAppender.appendDrainedEvents(
                                               List.of(event), ctx, storage, clock, codec);
        }
        Instant            now    = clock.instant();
        List<OutboxRecord> claimed = storage.claimBatch(batchSize, now);
        for (OutboxRecord r : claimed) {
            storage.markPublished(r.outboxId());
        }
        bh.consume(claimed);
    }
}

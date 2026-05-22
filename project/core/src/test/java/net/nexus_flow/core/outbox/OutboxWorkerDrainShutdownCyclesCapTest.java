package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link OutboxConfig#drainShutdownCycles()} cap.
 *
 * <p>Without a cap, a pathological storage that always returns eligible rows would make {@link
 * OutboxWorker#shutdown()} loop forever in the {@code drainOnShutdown} branch. The implementation
 * bounds the loop at {@code drainShutdownCycles} (default 100). This test installs a storage that
 * always claims-back what's available, sets a tight cap of 3, and asserts the worker calls {@code
 * claimBatch} at most 3 times during drain.
 */
class OutboxWorkerDrainShutdownCyclesCapTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggId) {
            super(aggId);
        }
    }

    /** Wraps {@link InMemoryOutboxStorage} and counts {@code claimBatch} invocations. */
    static final class CountingStorage implements OutboxStorage {
        final AtomicInteger                 claimBatchCalls = new AtomicInteger();
        private final InMemoryOutboxStorage delegate;

        CountingStorage(InMemoryOutboxStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(OutboxRecord record) {
            delegate.append(record);
        }

        @Override
        public List<OutboxRecord> claimBatch(int max, Instant now) {
            claimBatchCalls.incrementAndGet();
            return delegate.claimBatch(max, now);
        }

        @Override
        public void markPublished(OutboxId id) {
            delegate.markPublished(id);
        }

        @Override
        public void markFailed(OutboxId id, Throwable cause, Instant nextRetryAt) {
            delegate.markFailed(id, cause, nextRetryAt);
        }

        @Override
        public void markFailedTerminal(OutboxId id, Throwable cause) {
            delegate.markFailedTerminal(id, cause);
        }

        @Override
        public void releaseToReady(OutboxId id) {
            delegate.releaseToReady(id);
        }
    }

    private static OutboxRecord pendingTick(
            String aggId, long seq, Instant now, JavaSerializationOutboxPayloadCodec codec) {
        return new OutboxRecord(
                OutboxId.next(),
                IdempotencyKey.of("tick-" + seq),
                Tick.class.getName(),
                aggId,
                seq,
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                MessageId.random(),
                Tick.class,
                codec.encode(new Tick(aggId)),
                now,
                OutboxStatus.PENDING,
                0,
                null,
                null,
                null,
                null);
    }

    @Test
    void drainShutdownCycles_capsTheDrainLoop() {
        InMemoryOutboxStorage               backing = new InMemoryOutboxStorage();
        CountingStorage                     storage = new CountingStorage(backing);
        Clock                               clock   = Clock.systemUTC();
        Instant                             now     = clock.instant();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();

        // Append many more rows than the cap × batchSize so the drain would
        // never finish on its own — only the cap can stop it.
        List<OutboxRecord> appended = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            OutboxRecord r = pendingTick("agg-" + i, i, now, codec);
            storage.append(r);
            appended.add(r);
        }

        int          cap = 3;
        OutboxConfig cfg =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .autoStartWorker(false)
                        .drainOnShutdown(true)
                        .drainShutdownCycles(cap)
                        .workerBatchSize(1)
                        .workerShutdownGrace(Duration.ofMillis(200))
                        .build();

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            EventBus bus = runtime.events();
            bus.register(
                         new AbstractDomainEventListener<Tick>() {
                             @Override
                             public void handle(Tick event) {
                                 // no-op listener
                             }
                         });

            OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());
            // Do NOT call start() — only the drain-on-shutdown branch should run.
            worker.shutdown();

            int observed = storage.claimBatchCalls.get();
            assertTrue(
                       observed <= cap,
                       "drain loop must respect drainShutdownCycles cap; configured="
                               + cap
                               + " observed="
                               + observed);
            assertTrue(
                       observed >= 1,
                       "drain loop must run at least once when drainOnShutdown=true; observed=" + observed);
        }
    }

    @Test
    void drainShutdownCycles_stopsEarlyWhenStorageIsEmpty() {
        InMemoryOutboxStorage               backing = new InMemoryOutboxStorage();
        CountingStorage                     storage = new CountingStorage(backing);
        Clock                               clock   = Clock.systemUTC();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();

        OutboxConfig cfg =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .autoStartWorker(false)
                        .drainOnShutdown(true)
                        .drainShutdownCycles(100)
                        .workerShutdownGrace(Duration.ofMillis(200))
                        .build();

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            OutboxWorker worker = new OutboxWorker(cfg, runtime.events(), ErrorPolicy.failFast());
            worker.shutdown();

            // Empty storage returns 0 immediately; the worker should call claimBatch
            // exactly once and then exit the drain loop on the first empty batch.
            assertTrue(
                       storage.claimBatchCalls.get() == 1,
                       "drain loop must exit on first empty batch; observed=" + storage.claimBatchCalls.get());
        }
    }
}

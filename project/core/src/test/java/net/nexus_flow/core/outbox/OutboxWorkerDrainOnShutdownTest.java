package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
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
 * exercises {@code OutboxConfig.drainOnShutdown(true)}.
 *
 * <p>Builds an {@link OutboxWorker} manually with {@code autoStartWorker=false} so the daemon loop
 * never runs; appends N PENDING rows by hand; calls {@link OutboxWorker#shutdown()}; asserts that
 * the drain-on-shutdown branch published every row before the worker stops.
 */
class OutboxWorkerDrainOnShutdownTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggId) {
            super(aggId);
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
    void drainOnShutdown_true_drainsAllPendingRowsBeforeStopping() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        Clock                               clock   = Clock.systemUTC();
        Instant                             now     = clock.instant();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();

        for (int i = 0; i < 5; i++) {
            storage.append(pendingTick("agg-" + i, i, now, codec));
        }
        assertEquals(5, storage.snapshot().size());

        OutboxConfig cfg =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .autoStartWorker(false)
                        .drainOnShutdown(true)
                        .build();

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            EventBus      bus       = runtime.events();
            AtomicInteger published = new AtomicInteger();
            bus.register(
                         new AbstractDomainEventListener<Tick>() {
                             @Override
                             public void handle(Tick event) {
                                 published.incrementAndGet();
                             }
                         });

            OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());
            // Do NOT call worker.start(): we want shutdown() to do
            // all the work via the drainOnShutdown branch.
            worker.shutdown();

            assertEquals(
                         5,
                         published.get(),
                         "drainOnShutdown=true must deliver every PENDING row before stopping");
            for (OutboxRecord r : storage.snapshot()) {
                assertEquals(
                             OutboxStatus.PUBLISHED,
                             r.status(),
                             "row remained " + r.status() + " after drain; row=" + r);
            }
        }
    }

    @Test
    void drainOnShutdown_false_leavesRowsPending() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        Clock                               clock   = Clock.systemUTC();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        storage.append(pendingTick("agg-x", 1, clock.instant(), codec));

        OutboxConfig cfg =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .autoStartWorker(false)
                        .drainOnShutdown(false)
                        .build();

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            EventBus      bus       = runtime.events();
            AtomicInteger published = new AtomicInteger();
            bus.register(
                         new AbstractDomainEventListener<Tick>() {
                             @Override
                             public void handle(Tick event) {
                                 published.incrementAndGet();
                             }
                         });

            OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());
            worker.shutdown();

            assertEquals(0, published.get(), "drainOnShutdown=false must NOT drain on shutdown");
            for (OutboxRecord r : storage.snapshot()) {
                assertEquals(OutboxStatus.PENDING, r.status());
            }
        }
    }
}

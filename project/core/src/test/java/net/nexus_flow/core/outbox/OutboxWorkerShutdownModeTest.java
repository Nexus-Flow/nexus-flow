package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
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
 * Pins the {@link OutboxWorker#shutdown(ShutdownMode)} overload contract:
 *
 * <ul>
 * <li>{@link ShutdownMode#GRACEFUL} drains every eligible row through {@link
 * OutboxWorker#drainOnce()} (up to the configured cycle cap) BEFORE cancelling the worker
 * token, regardless of {@link OutboxConfig#drainOnShutdown()}.
 * <li>{@link ShutdownMode#IMMEDIATE} skips the drain entirely, regardless of {@link
 * OutboxConfig#drainOnShutdown()}. Pending rows stay PENDING for the next replica / restart.
 * <li>{@link OutboxWorker#shutdown()} (no-arg, backwards-compat) maps to the config-driven
 * default: {@code drainOnShutdown=true → GRACEFUL}, {@code false → IMMEDIATE}.
 * <li>The overload rejects {@code null} mode with {@link NullPointerException}.
 * <li>Idempotent: a second call after the first is a no-op irrespective of mode.
 * </ul>
 */
class OutboxWorkerShutdownModeTest {

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
    void graceful_drainsPendingRows_evenIfConfigDrainOnShutdownIsFalse() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        Clock                               clock   = Clock.systemUTC();
        Instant                             now     = clock.instant();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        for (int i = 0; i < 3; i++) {
            storage.append(pendingTick("agg-" + i, i, now, codec));
        }

        OutboxConfig cfg =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .autoStartWorker(false)
                        .drainOnShutdown(false) // config says NO drain
                        .workerShutdownGrace(Duration.ofMillis(300))
                        .build();

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            EventBus      bus       = runtime.events();
            AtomicInteger delivered = new AtomicInteger();
            bus.register(
                         new AbstractDomainEventListener<Tick>() {
                             @Override
                             public void handle(Tick event) {
                                 delivered.incrementAndGet();
                             }
                         });
            OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());

            // Explicit GRACEFUL — must override the config default and drain everything.
            worker.shutdown(ShutdownMode.GRACEFUL);

            assertEquals(
                         3,
                         delivered.get(),
                         "ShutdownMode.GRACEFUL must drain ALL pending rows regardless of config");
            for (OutboxRecord r : storage.snapshot()) {
                assertEquals(OutboxStatus.PUBLISHED, r.status());
            }
        }
    }

    @Test
    void immediate_skipsDrain_evenIfConfigDrainOnShutdownIsTrue() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        Clock                               clock   = Clock.systemUTC();
        Instant                             now     = clock.instant();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        for (int i = 0; i < 3; i++) {
            storage.append(pendingTick("agg-" + i, i, now, codec));
        }

        OutboxConfig cfg =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .autoStartWorker(false)
                        .drainOnShutdown(true) // config says YES drain
                        .workerShutdownGrace(Duration.ofMillis(300))
                        .build();

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            EventBus      bus       = runtime.events();
            AtomicInteger delivered = new AtomicInteger();
            bus.register(
                         new AbstractDomainEventListener<Tick>() {
                             @Override
                             public void handle(Tick event) {
                                 delivered.incrementAndGet();
                             }
                         });
            OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());

            // Explicit IMMEDIATE — must override the config default and skip drain.
            worker.shutdown(ShutdownMode.IMMEDIATE);

            assertEquals(
                         0, delivered.get(), "ShutdownMode.IMMEDIATE must skip drain regardless of config");
            for (OutboxRecord r : storage.snapshot()) {
                assertEquals(OutboxStatus.PENDING, r.status(), "rows must remain PENDING after IMMEDIATE");
            }
        }
    }

    @Test
    void noArgShutdown_picksGraceful_whenConfigDrainOnShutdownTrue() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        Clock                               clock   = Clock.systemUTC();
        Instant                             now     = clock.instant();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        storage.append(pendingTick("agg-x", 0, now, codec));

        OutboxConfig cfg =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .autoStartWorker(false)
                        .drainOnShutdown(true)
                        .build();

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            EventBus      bus       = runtime.events();
            AtomicInteger delivered = new AtomicInteger();
            bus.register(
                         new AbstractDomainEventListener<Tick>() {
                             @Override
                             public void handle(Tick event) {
                                 delivered.incrementAndGet();
                             }
                         });
            OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());

            worker.shutdown(); // no-arg
            assertEquals(1, delivered.get(), "no-arg shutdown with drainOnShutdown=true → GRACEFUL");
        }
    }

    @Test
    void noArgShutdown_picksImmediate_whenConfigDrainOnShutdownFalse() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        Clock                               clock   = Clock.systemUTC();
        Instant                             now     = clock.instant();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        storage.append(pendingTick("agg-x", 0, now, codec));

        OutboxConfig cfg =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .autoStartWorker(false)
                        .drainOnShutdown(false)
                        .build();

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            EventBus      bus       = runtime.events();
            AtomicInteger delivered = new AtomicInteger();
            bus.register(
                         new AbstractDomainEventListener<Tick>() {
                             @Override
                             public void handle(Tick event) {
                                 delivered.incrementAndGet();
                             }
                         });
            OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());

            worker.shutdown(); // no-arg
            assertEquals(0, delivered.get(), "no-arg shutdown with drainOnShutdown=false → IMMEDIATE");
        }
    }

    @Test
    void shutdownWithNullMode_throwsNpe() {
        OutboxStorage      storage = new InMemoryOutboxStorage();
        OutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        OutboxConfig       cfg     = OutboxConfig.builder(storage, codec).autoStartWorker(false).build();
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            OutboxWorker worker = new OutboxWorker(cfg, runtime.events(), ErrorPolicy.failFast());
            assertThrows(NullPointerException.class, () -> worker.shutdown(null));
            // Cleanup: a no-arg call should still succeed and stop the worker.
            worker.shutdown();
        }
    }

    @Test
    void shutdownIsIdempotent_regardlessOfMode() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        OutboxConfig                        cfg     = OutboxConfig.builder(storage, codec).autoStartWorker(false).build();
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            OutboxWorker worker = new OutboxWorker(cfg, runtime.events(), ErrorPolicy.failFast());
            worker.shutdown(ShutdownMode.GRACEFUL);
            // Second and third calls must be no-ops even with a different mode.
            assertDoesNotThrow(() -> worker.shutdown(ShutdownMode.IMMEDIATE));
            assertDoesNotThrow(() -> worker.shutdown());
        }
    }
}

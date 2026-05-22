package net.nexus_flow.core.outbox;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests that pin the OutboxWorker → DeadLetterHandler hook end-to-end. The
 * handler is sourced from {@link OutboxConfig#deadLetterHandler()} (first-class config
 * field, no setter). A handler that throws does not poison the worker. Sweep recovers
 * crashed IN_FLIGHT rows via the config-driven visibility timeout.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class OutboxDeadLetterIntegrationTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class TickAgg extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void tick() {
            recordEvent(new Tick("agg-1"));
        }
    }

    private OutboxWorker worker;
    private EventBus     bus;

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.close();
        }
        if (bus != null) {
            bus.closeAll();
        }
    }

    @Test
    void deadLetterHandler_isInvokedOnceWhenRowReachesFailedTerminal() throws Exception {
        InMemoryOutboxStorage         storage     = new InMemoryOutboxStorage();
        AtomicReference<OutboxRecord> seen        = new AtomicReference<>();
        AtomicInteger                 invocations = new AtomicInteger();
        DeadLetterHandler             capturing   = (row, cause) -> {
                                                      invocations.incrementAndGet();
                                                      seen.set(row);
                                                  };

        bus = EventBus.newInstance();
        bus.register(new AbstractDomainEventListener<Tick>() {
            @Override
            public void handle(Tick event) {
                throw new RuntimeException("listener always fails");
            }
        });

        OutboxConfig cfg = OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                .workerMaxAttempts(2)
                .workerBackoffBase(Duration.ofMillis(1))
                .workerBackoffMax(Duration.ofMillis(5))
                .workerPollInterval(Duration.ofMillis(10))
                .deadLetterHandler(capturing)
                .build();

        TickAgg agg = new TickAgg();
        agg.tick();
        OutboxAppender.appendDrainedEvents(
                                           agg.drainEvents(), ExecutionContext.root(),
                                           storage, Clock.systemUTC(), new JavaSerializationOutboxPayloadCodec());

        worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());
        worker.start();

        await().atMost(5, TimeUnit.SECONDS).until(() -> invocations.get() >= 1);
        assertNotNull(seen.get());
        assertEquals(OutboxStatus.FAILED_TERMINAL,
                     storage.findById(seen.get().outboxId()).status());
        assertEquals(1, invocations.get(),
                     "the handler MUST be invoked exactly once per terminal transition");
    }

    @Test
    void deadLetterHandler_throwing_doesNotPoisonTheWorker() throws Exception {
        InMemoryOutboxStorage storage     = new InMemoryOutboxStorage();
        AtomicInteger         invocations = new AtomicInteger();
        DeadLetterHandler     exploding   = (row, cause) -> {
                                              invocations.incrementAndGet();
                                              throw new RuntimeException("handler crash");
                                          };

        bus = EventBus.newInstance();
        bus.register(new AbstractDomainEventListener<Tick>() {
            @Override
            public void handle(Tick event) {
                throw new RuntimeException("listener always fails");
            }
        });

        OutboxConfig cfg = OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                .workerMaxAttempts(2)
                .workerBackoffBase(Duration.ofMillis(1))
                .workerBackoffMax(Duration.ofMillis(5))
                .workerPollInterval(Duration.ofMillis(10))
                .deadLetterHandler(exploding)
                .build();

        TickAgg agg = new TickAgg();
        agg.tick();
        agg.tick();
        OutboxAppender.appendDrainedEvents(
                                           agg.drainEvents(), ExecutionContext.root(),
                                           storage, Clock.systemUTC(), new JavaSerializationOutboxPayloadCodec());

        worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());
        worker.start();

        await().atMost(8, TimeUnit.SECONDS).until(() -> invocations.get() >= 2);
        // Both rows must reach FAILED_TERMINAL despite the handler throwing each time.
        long terminalCount = storage.snapshot().stream()
                .filter(r -> r.status() == OutboxStatus.FAILED_TERMINAL).count();
        assertEquals(2, terminalCount);
    }

    @Test
    void deadLetterHandler_defaultLogOnly_isWiredViaConfig() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        OutboxConfig          cfg     = OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                .build();
        assertEquals(DeadLetterHandler.LOG_ONLY, cfg.deadLetterHandler(),
                     "config default MUST be LOG_ONLY so a fresh deployment still surfaces"
                             + " terminal failures");
    }

    @Test
    void staleClaimVisibilityTimeout_isConfigurable() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        OutboxConfig          cfg     = OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                .workerPollInterval(Duration.ofMillis(100))
                .staleClaimVisibilityTimeout(Duration.ofSeconds(5))
                .build();
        assertEquals(Duration.ofSeconds(5), cfg.staleClaimVisibilityTimeout());
    }

    @Test
    void staleClaimVisibilityTimeout_isRespected_byVisibilityTimeoutSweep() throws Exception {
        // Regression for the dead-knob bug: OutboxWorker previously derived its own clamp
        // (max(30s, pollInterval×10)) and silently ignored the configured value. After the
        // fix, the user-configured threshold is the single source of truth.
        Instant               t0      = Instant.parse("2026-05-25T10:00:00Z");
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(
                java.time.Clock.fixed(t0, java.time.ZoneOffset.UTC));
        OutboxConfig          cfg     = OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                .workerPollInterval(Duration.ofMillis(100))
                .staleClaimVisibilityTimeout(Duration.ofSeconds(2))
                .clock(java.time.Clock.fixed(t0.plusSeconds(5), java.time.ZoneOffset.UTC))
                .autoStartWorker(false)
                .build();
        // Append + claim → row goes IN_FLIGHT, claimedAt = t0.
        OutboxRecord row = new OutboxRecord(
                OutboxId.next(),
                new IdempotencyKey("k-1"),
                "TestAgg", "agg-1", 0L,
                net.nexus_flow.core.runtime.ids.TraceId.random(),
                net.nexus_flow.core.runtime.ids.CorrelationId.random(),
                net.nexus_flow.core.runtime.ids.CausationId.ROOT,
                net.nexus_flow.core.runtime.ids.MessageId.random(),
                String.class,
                "x".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                t0,
                OutboxStatus.PENDING, 0, null, null, null, null, null);
        storage.append(row);
        storage.claimBatch(10, t0);
        // 5s after the claim, with stale=2s configured, the sweep MUST recover the row.
        int recovered = storage.sweepStaleClaims(cfg.staleClaimVisibilityTimeout(), t0.plusSeconds(5));
        assertEquals(1, recovered,
                     "the user-configured staleClaimVisibilityTimeout=2s MUST drive the sweep,"
                             + " not the previous hardcoded 30s lower bound");
    }

    @Test
    void staleClaimVisibilityTimeout_rejectsBelowPollInterval() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        OutboxConfig.Builder  builder =
                OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                        .workerPollInterval(Duration.ofSeconds(10))
                        .staleClaimVisibilityTimeout(Duration.ofMillis(50));
        assertTrue(
                   org.junit.jupiter.api.Assertions.assertThrows(
                                                                 IllegalArgumentException.class, builder::build)
                           .getMessage()
                           .contains("staleClaimVisibilityTimeout"));
    }
}

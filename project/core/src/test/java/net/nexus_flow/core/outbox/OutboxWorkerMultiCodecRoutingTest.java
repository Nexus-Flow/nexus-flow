package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Pins the end-to-end multi-codec routing pipeline:
 *
 * <ol>
 * <li>{@link OutboxAppender#toRecord} captures {@link OutboxPayloadCodec#codecId()} into {@link
 * OutboxRecord#codecId()}.
 * <li>{@link OutboxWorker} resolves the codec via {@link OutboxConfig#codecRegistry()} when the
 * row's {@code codecId} is non-null and a registry is configured — preferring the
 * registry-resolved codec over the primary {@link OutboxConfig#codec()}.
 * <li>Legacy rows ({@code codecId == null}) fall through to the primary codec, preserving
 * backwards compatibility with rows persisted by prior framework versions.
 * <li>An unregistered {@code codecId} fails the row to {@link OutboxStatus#FAILED_TERMINAL}
 * rather than silently routing through the primary codec (a misrouted decode would produce
 * garbage; explicit failure is the right operator-visible signal).
 * </ol>
 */
class OutboxWorkerMultiCodecRoutingTest {

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
            recordEvent(new Tick("agg-mc"));
        }
    }

    /**
     * A second codec that delegates encode/decode to JavaSerialization but advertises a different id.
     */
    static final class JavaV2Codec implements OutboxPayloadCodec {
        static final String                               ID          = "java-v2";
        private final JavaSerializationOutboxPayloadCodec delegate    =
                new JavaSerializationOutboxPayloadCodec();
        final AtomicInteger                               encodeCount = new AtomicInteger();
        final AtomicInteger                               decodeCount = new AtomicInteger();

        @Override
        public String codecId() {
            return ID;
        }

        @Override
        public byte[] encode(DomainEvent event) {
            encodeCount.incrementAndGet();
            return delegate.encode(event);
        }

        @Override
        public DomainEvent decode(byte[] bytes, Class<?> payloadType) {
            decodeCount.incrementAndGet();
            return delegate.decode(bytes, payloadType);
        }
    }

    @Test
    void appender_capturesCodecIdIntoRowField() {
        Clock                 clock   = Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaV2Codec();

        TickAgg agg = new TickAgg();
        agg.tick();
        OutboxAppender.appendDrainedEvents(
                                           agg.drainEvents(), ExecutionContext.root(), storage, clock, codec);

        OutboxRecord row = storage.snapshot().getFirst();
        assertEquals("java-v2", row.codecId(), "appender MUST capture codec.codecId() into the row");
    }

    @Test
    void appender_codecLessOverload_leavesCodecIdNull() {
        Clock                 clock   = Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        TickAgg agg = new TickAgg();
        agg.tick();
        OutboxAppender.appendDrainedEvents(agg.drainEvents(), ExecutionContext.root(), storage, clock);

        OutboxRecord row = storage.snapshot().getFirst();
        assertEquals(
                     null,
                     row.codecId(),
                     "the codec-less append overload MUST leave codecId null (backwards-compat with rows"
                             + " written before the field existed)");
    }

    @Test
    void worker_routesRowToRegistryCodec_overPrimaryCodec() {
        Clock                 clock   = Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        // The row is written with the java-v2 codec (carries codecId = "java-v2").
        JavaV2Codec                         v2      = new JavaV2Codec();
        JavaSerializationOutboxPayloadCodec primary = new JavaSerializationOutboxPayloadCodec();

        TickAgg agg = new TickAgg();
        agg.tick();
        OutboxAppender.appendDrainedEvents(
                                           agg.drainEvents(), ExecutionContext.root(), storage, clock, v2);

        EventBus     bus  = EventBus.newInstance();
        List<String> seen = new CopyOnWriteArrayList<>();
        bus.register(
                     new AbstractDomainEventListener<Tick>() {
                         @Override
                         public void handle(Tick event) {
                             seen.add(event.idempotencyKey());
                         }
                     });

        // Registry maps "java-v2" → v2. The primary codec is the plain Java codec ("java-v1") and
        // would decode the bytes too (both are Java serialization under the hood), but we assert via
        // v2.decodeCount that the registry path was taken.
        OutboxPayloadCodecRegistry registry = new MapOutboxPayloadCodecRegistry(Map.of("java-v2", v2));
        OutboxConfig               config   =
                OutboxConfig.builder(storage, primary)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerPollInterval(Duration.ofMillis(50))
                        .codecRegistry(registry)
                        .build();
        OutboxWorker               worker   = new OutboxWorker(config, bus, ErrorPolicy.failFast());

        int processed = worker.drainOnce();
        assertEquals(1, processed);
        assertEquals(List.of("agg-mc:0"), seen);
        assertEquals(
                     1,
                     v2.decodeCount.get(),
                     "worker MUST decode through the registry-resolved codec, not the primary codec");
        assertEquals(
                     OutboxStatus.PUBLISHED,
                     storage.snapshot().getFirst().status(),
                     "row should be PUBLISHED after a successful routed decode");
    }

    @Test
    void worker_fallsBackToPrimaryCodec_whenRowCodecIdIsNull() {
        Clock                               clock   = Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage(clock);
        JavaSerializationOutboxPayloadCodec primary = new JavaSerializationOutboxPayloadCodec();

        // Build a legacy-style row (codecId = null) via the legacy 18-arg OutboxRecord constructor.
        TickAgg agg = new TickAgg();
        agg.tick();
        DomainEvent  original  = agg.drainEvents().getFirst();
        OutboxRecord legacyRow =
                new OutboxRecord(
                        OutboxId.next(),
                        IdempotencyKey.from(original),
                        original.getClass().getName(),
                        original.getAggregateId(),
                        ((AbstractDomainEvent) original).getSequenceNumber(),
                        ExecutionContext.root().traceId(),
                        ExecutionContext.root().correlationId(),
                        ExecutionContext.root().causationId(),
                        ExecutionContext.root().messageId(),
                        original.getClass(),
                        primary.encode(original),
                        clock.instant(),
                        OutboxStatus.PENDING,
                        0,
                        null,
                        null,
                        null,
                        null);
        storage.append(legacyRow);
        assertEquals(null, storage.snapshot().getFirst().codecId(), "fixture row has null codecId");

        EventBus     bus  = EventBus.newInstance();
        List<String> seen = new CopyOnWriteArrayList<>();
        bus.register(
                     new AbstractDomainEventListener<Tick>() {
                         @Override
                         public void handle(Tick event) {
                             seen.add(event.idempotencyKey());
                         }
                     });

        // Even with a registry configured, the null codecId on the row forces the fallback to
        // primary codec — the registry is only consulted when both registry AND codecId are non-null.
        OutboxPayloadCodecRegistry registry =
                new MapOutboxPayloadCodecRegistry(Map.of("java-v2", new JavaV2Codec()));
        OutboxConfig               config   =
                OutboxConfig.builder(storage, primary)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerPollInterval(Duration.ofMillis(50))
                        .codecRegistry(registry)
                        .build();
        OutboxWorker               worker   = new OutboxWorker(config, bus, ErrorPolicy.failFast());

        assertEquals(1, worker.drainOnce());
        assertEquals(List.of("agg-mc:0"), seen, "legacy row decoded via primary codec");
        assertEquals(OutboxStatus.PUBLISHED, storage.snapshot().getFirst().status());
    }

    @Test
    void worker_unregisteredCodecId_terminalFailsTheRow() {
        Clock                 clock   = Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        // Row was written by java-v2 — but the worker's registry only knows java-v1.
        JavaV2Codec                         v2      = new JavaV2Codec();
        JavaSerializationOutboxPayloadCodec primary = new JavaSerializationOutboxPayloadCodec();
        TickAgg                             agg     = new TickAgg();
        agg.tick();
        OutboxAppender.appendDrainedEvents(
                                           agg.drainEvents(), ExecutionContext.root(), storage, clock, v2);

        EventBus                   bus               = EventBus.newInstance();
        OutboxPayloadCodecRegistry registryMissingV2 =
                new MapOutboxPayloadCodecRegistry(Map.of("java-v1", primary));
        OutboxConfig               config            =
                OutboxConfig.builder(storage, primary)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerPollInterval(Duration.ofMillis(50))
                        .codecRegistry(registryMissingV2)
                        .workerMaxAttempts(1)
                        .build();
        OutboxWorker               worker            = new OutboxWorker(config, bus, ErrorPolicy.failFast());

        worker.drainOnce();
        OutboxRecord row = storage.snapshot().getFirst();
        assertEquals(
                     OutboxStatus.FAILED_TERMINAL,
                     row.status(),
                     "an unregistered codecId MUST surface as a hard failure, not a silent fallback to the"
                             + " primary codec (would deserialize garbage)");
        assertNotNull(row.lastError(), "the failure cause must be persisted for operator triage");
        assertTrue(
                   row.lastError().contains("java-v2"),
                   "lastError must mention the unresolved codecId so the operator can register it; got: "
                           + row.lastError());
    }

    @Test
    void mapRegistry_keyMustMatchCodecId() {
        JavaV2Codec              v2 = new JavaV2Codec();
        IllegalArgumentException ex =
                assertThrows(
                             IllegalArgumentException.class,
                             () -> new MapOutboxPayloadCodecRegistry(Map.of("typo-v2", v2)));
        assertTrue(
                   ex.getMessage().contains("typo-v2") && ex.getMessage().contains("java-v2"),
                   "MapOutboxPayloadCodecRegistry MUST reject mismatched key/codecId at construction to"
                           + " catch operator typos; got: "
                           + ex.getMessage());
    }
}

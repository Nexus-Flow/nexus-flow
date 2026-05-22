package net.nexus_flow.core.ring.event;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.Serial;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.inbox.InMemoryInboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.TestRingConnections;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.RingFrame;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link EventBusRingInboundHandler} — the receiver-side counterpart of {@link
 * RingEventBusBridge} / {@link RingOutboxBridge}. Without this handler
 * the {@link net.nexus_flow.core.ring.RingRuntime} wiring dropped every inbound EVENT silently.
 */
class EventBusRingInboundHandlerTest {

    static final class DemoEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String externalKey;

        DemoEvent(String aggregateId, String externalKey) {
            super(aggregateId);
            this.externalKey = externalKey;
        }

        /**
         * Override the default {@code aggregateId:sequenceNumber} key — the test creates events
         * outside of an aggregate, so {@code sequenceNumber} is never stamped. Tests that want
         * to exercise dedup pin a stable external key here.
         */
        @Override
        public String idempotencyKey() {
            return externalKey;
        }
    }

    static final class CountingListener extends AbstractDomainEventListener<DemoEvent> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void handle(DemoEvent event) {
            calls.incrementAndGet();
        }
    }

    private static RingFrame eventFrameFrom(
            DemoEvent event, JavaSerializationOutboxPayloadCodec codec, String codecId) {
        byte[]            payload  = codec.encode(event);
        RingEventEnvelope envelope = new RingEventEnvelope(
                PeerId.of("pod-sender"),
                1L,
                event.getClass().getName(),
                codecId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                payload);
        return RingFrame.wrapping(FrameType.EVENT, envelope.encode());
    }

    @Test
    void inboundEvent_isPublishedToLocalEventBus() throws IOException {
        CountingListener listener = new CountingListener();
        try (FlowRuntime runtime = FlowRuntime.builder().handler(listener).build()) {
            EventBus                            bus     = runtime.events();
            JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
            String                              codecId = codec.codecId();
            EventBusRingInboundHandler          handler = EventBusRingInboundHandler.builder()
                    .eventBus(bus)
                    .codec(codec)
                    .expectedCodecId(codecId)
                    .build();
            RingConnection                      conn    = TestRingConnections.stub();
            DemoEvent                           ev      = new DemoEvent("agg-1", "test-key-001");

            handler.onEvent(conn, eventFrameFrom(ev, codec, codecId));

            await().atMost(2, TimeUnit.SECONDS).until(() -> listener.calls.get() == 1);
            assertEquals(1, listener.calls.get(),
                         "inbound EVENT MUST be published to the local EventBus exactly once");
        }
    }

    @Test
    void mismatchedCodecId_dropsEvent() throws IOException {
        CountingListener listener = new CountingListener();
        try (FlowRuntime runtime = FlowRuntime.builder().handler(listener).build()) {
            JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
            EventBusRingInboundHandler          handler = EventBusRingInboundHandler.builder()
                    .eventBus(runtime.events())
                    .codec(codec)
                    .expectedCodecId("expected-only")
                    .build();
            RingConnection                      conn    = TestRingConnections.stub();
            DemoEvent                           ev      = new DemoEvent("agg-1", "test-key-001");

            assertDoesNotThrow(() -> handler.onEvent(
                                                     conn, eventFrameFrom(ev, codec, "some-other-codec")));
            assertEquals(0, listener.calls.get(),
                         "unknown codec id MUST be dropped, not dispatched");
        }
    }

    @Test
    void inboxDedup_dispatchesOnce_acrossDuplicates() throws IOException {
        CountingListener listener = new CountingListener();
        try (FlowRuntime runtime = FlowRuntime.builder().handler(listener).build()) {
            JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
            InMemoryInboxStorage                inbox   = new InMemoryInboxStorage();
            EventBusRingInboundHandler          handler = EventBusRingInboundHandler.builder()
                    .eventBus(runtime.events())
                    .codec(codec)
                    .expectedCodecId(codec.codecId())
                    .inbox(inbox)
                    .clock(Clock.systemUTC())
                    .build();
            RingConnection                      conn    = TestRingConnections.stub();
            DemoEvent                           ev      = new DemoEvent("agg-1", "test-key-001");
            RingFrame                           frame   = eventFrameFrom(ev, codec, codec.codecId());

            // First delivery → listener fires once.
            handler.onEvent(conn, frame);
            // Second delivery (live + durable duplicate) → must be skipped by inbox dedup.
            handler.onEvent(conn, frame);
            // Third for good measure.
            handler.onEvent(conn, frame);

            await().atMost(2, TimeUnit.SECONDS).until(() -> listener.calls.get() == 1);
            assertEquals(1, listener.calls.get(),
                         "inbox-dedup must collapse repeated deliveries to a single dispatch");
        }
    }

    @Test
    void unknownPayloadType_dropsEventWithoutThrowing() throws IOException {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
            EventBusRingInboundHandler          handler = EventBusRingInboundHandler.builder()
                    .eventBus(runtime.events())
                    .codec(codec)
                    .expectedCodecId(codec.codecId())
                    .build();
            // Construct an envelope whose payloadType references a class that is not loadable.
            RingEventEnvelope envelope = new RingEventEnvelope(
                    PeerId.of("pod-sender"),
                    1L,
                    "com.unknown.NoSuch",
                    codec.codecId(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    new byte[]{0});
            RingFrame         frame    = RingFrame.wrapping(FrameType.EVENT, envelope.encode());
            assertDoesNotThrow(() -> handler.onEvent(TestRingConnections.stub(), frame));
        }
    }
}

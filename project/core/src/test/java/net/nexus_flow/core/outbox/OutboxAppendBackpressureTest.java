package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowError;
import org.junit.jupiter.api.Test;

/**
 * Pins the append-side backpressure contract:
 *
 * <ol>
 * <li>{@link OutboxAppendBackpressureSettings.Policy#UNLIMITED} (default) accepts every
 * batch regardless of pending count.
 * <li>{@link OutboxAppendBackpressureSettings.Policy#REJECT} throws {@link
 * OutboxAppendRejectedException} when {@link OutboxStorage#pendingCount()} crosses
 * {@code maxPendingRows}.
 * <li>{@link OutboxAppendBackpressureSettings.Policy#DROP} silently skips the durable
 * append; the handler completes successfully.
 * <li>A storage that returns {@code -1L} from {@link OutboxStorage#pendingCount()} bypasses
 * backpressure entirely — the runtime cannot decide saturation without a count.
 * </ol>
 */
class OutboxAppendBackpressureTest {

    static final class TouchedEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        TouchedEvent(String aggregateId) {
            super(aggregateId);
        }

        @Override
        public String idempotencyKey() {
            return getClass().getSimpleName() + ":" + getId();
        }
    }

    static final class Thing extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void touch() {
            recordEvent(new TouchedEvent("agg"));
        }
    }

    record CmdTouch() {
    }

    static final class TouchHandler extends AbstractReturnCommandHandler<CmdTouch, String> {
        @Override
        public String handle(CmdTouch c) {
            new Thing().touch();
            return "ok";
        }
    }

    static final class NoopListener extends AbstractDomainEventListener<TouchedEvent> {
        int seen;

        @Override
        public void handle(TouchedEvent event) {
            seen++;
        }
    }

    @Test
    void unlimited_default_acceptsAppendsAtAnyPendingCount() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        // Pre-seed a bunch of pending rows.
        for (int i = 0; i < 50; i++) {
            storage.append(new OutboxRecord(
                    OutboxId.next(),
                    IdempotencyKey.of("pre-" + i),
                    "Demo", "agg", i,
                    net.nexus_flow.core.runtime.ids.TraceId.random(),
                    net.nexus_flow.core.runtime.ids.CorrelationId.random(),
                    net.nexus_flow.core.runtime.ids.CausationId.ROOT,
                    net.nexus_flow.core.runtime.ids.MessageId.random(),
                    Object.class, new byte[0],
                    java.time.Instant.parse("2026-05-28T12:00:00Z"),
                    OutboxStatus.PENDING, 0, null, null, null, null));
        }
        OutboxConfig cfg = OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                .autoStartWorker(false)
                .build();

        try (FlowRuntime runtime = FlowRuntime.builder()
                .outbox(cfg)
                .handlers(new TouchHandler())
                .build()) {
            runtime.commands().dispatchAndReturn(
                                                 Command.<CmdTouch>builder().body(new CmdTouch()).build());
            // The append succeeded — verify the new row is present.
            assertEquals(51L, storage.pendingCount());
        }
    }

    @Test
    void reject_aboveThreshold_throwsRejected() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        for (int i = 0; i < 10; i++) {
            storage.append(new OutboxRecord(
                    OutboxId.next(),
                    IdempotencyKey.of("pre-" + i),
                    "Demo", "agg", i,
                    net.nexus_flow.core.runtime.ids.TraceId.random(),
                    net.nexus_flow.core.runtime.ids.CorrelationId.random(),
                    net.nexus_flow.core.runtime.ids.CausationId.ROOT,
                    net.nexus_flow.core.runtime.ids.MessageId.random(),
                    Object.class, new byte[0],
                    java.time.Instant.parse("2026-05-28T12:00:00Z"),
                    OutboxStatus.PENDING, 0, null, null, null, null));
        }
        OutboxConfig cfg = OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                .autoStartWorker(false)
                .appendBackpressure(OutboxAppendBackpressureSettings.reject(5L))
                .build();

        try (FlowRuntime runtime = FlowRuntime.builder()
                .outbox(cfg)
                .handlers(new TouchHandler())
                .build()) {
            DispatchResult<String>         result  = runtime.commands().dispatchAndReturnResult(
                                                                                                Command.<CmdTouch>builder().body(
                                                                                                                                 new CmdTouch())
                                                                                                        .build(),
                                                                                                net.nexus_flow.core.runtime.ExecutionContext
                                                                                                        .root(),
                                                                                                net.nexus_flow.core.runtime.ErrorPolicy
                                                                                                        .failFast());
            DispatchResult.Failure<String> failure = assertInstanceOf(
                                                                      DispatchResult.Failure.class, result,
                                                                      "REJECT policy MUST surface as a failure");
            FlowError.Technical            tech    = assertInstanceOf(FlowError.Technical.class, failure.cause());
            assertInstanceOf(OutboxAppendRejectedException.class, tech.getCause(),
                             "the root cause MUST be OutboxAppendRejectedException");
            // No new row was appended — pending count stays at the pre-seeded value.
            assertEquals(10L, storage.pendingCount());
        }
    }

    @Test
    void drop_aboveThreshold_skipsAppend_butInlineDispatchFires() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        for (int i = 0; i < 5; i++) {
            storage.append(new OutboxRecord(
                    OutboxId.next(),
                    IdempotencyKey.of("pre-" + i),
                    "Demo", "agg", i,
                    net.nexus_flow.core.runtime.ids.TraceId.random(),
                    net.nexus_flow.core.runtime.ids.CorrelationId.random(),
                    net.nexus_flow.core.runtime.ids.CausationId.ROOT,
                    net.nexus_flow.core.runtime.ids.MessageId.random(),
                    Object.class, new byte[0],
                    java.time.Instant.parse("2026-05-28T12:00:00Z"),
                    OutboxStatus.PENDING, 0, null, null, null, null));
        }
        OutboxConfig cfg      = OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                // This test pins the DROP-backpressure shape that lets inline fan-out fire
                // even when the durable append is suppressed. Opt into InlinePlusOutbox so
                // the inline dispatch path is active.
                .deliveryStrategy(net.nexus_flow.core.outbox.EventDeliveryStrategy.inlinePlusOutbox())
                .autoStartWorker(false)
                .appendBackpressure(OutboxAppendBackpressureSettings.drop(3L))
                .build();
        NoopListener listener = new NoopListener();
        try (FlowRuntime runtime = FlowRuntime.builder()
                .outbox(cfg)
                .handlers(new TouchHandler(), listener)
                .build()) {
            runtime.commands().dispatchAndReturn(
                                                 Command.<CmdTouch>builder().body(new CmdTouch()).build());
            // DROP policy → no new row.
            assertEquals(5L, storage.pendingCount());
            // Inline fan-out still ran → listener saw the event.
            assertTrue(listener.seen >= 1,
                       "DROP policy MUST keep the inline dispatch alive so local listeners observe the event");
        }
    }

    @Test
    void settings_negativeOrZeroMax_rejected_whenPolicyIsNotUnlimited() {
        assertThrows(IllegalArgumentException.class,
                     () -> OutboxAppendBackpressureSettings.reject(0L));
        assertThrows(IllegalArgumentException.class,
                     () -> OutboxAppendBackpressureSettings.drop(0L));
    }

    @Test
    void unlimited_factoryConstant_isReusable() {
        assertEquals(OutboxAppendBackpressureSettings.Policy.UNLIMITED,
                     OutboxAppendBackpressureSettings.UNLIMITED.policy());
    }
}

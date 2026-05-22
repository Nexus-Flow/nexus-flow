package net.nexus_flow.core.ring.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.io.Serializable;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingTransportPrincipal;
import net.nexus_flow.core.ring.wire.ProtocolErrorCode;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RuntimeBackedLocalDispatchHandler} — the production-grade inbound handler that
 * bridges cross-pod COMMAND_REQ / QUERY_REQ frames to the bound {@link FlowRuntime}'s buses.
 * Without this handler the in-tree {@code RingRuntime.defaultLocalRejection} rejected every
 * inbound dispatch with {@link ProtocolErrorCode#NOT_FOUND}.
 */
class RuntimeBackedLocalDispatchHandlerTest {

    public record DemoCommand(String text) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public record DemoQuery(String id) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public record UnregisteredCommand(String x) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static final class CountingCommandHandler extends AbstractNoReturnCommandHandler<DemoCommand> {
        final AtomicInteger                calls = new AtomicInteger();
        final AtomicReference<DemoCommand> last  = new AtomicReference<>();

        @Override
        public void handle(DemoCommand cmd) {
            calls.incrementAndGet();
            last.set(cmd);
        }
    }

    private static final class CountingReturnHandler extends AbstractReturnCommandHandler<DemoCommand, String> {
        final AtomicInteger                calls = new AtomicInteger();
        final AtomicReference<DemoCommand> last  = new AtomicReference<>();

        @Override
        public String handle(DemoCommand cmd) {
            calls.incrementAndGet();
            last.set(cmd);
            return "done:" + cmd.text();
        }
    }

    private static final class CountingQueryHandler extends AbstractQueryHandler<DemoQuery, String> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String handle(DemoQuery q) {
            calls.incrementAndGet();
            return "answer:" + q.id();
        }
    }

    private static LocalDispatchHandler.LocalDispatchContext requestFor(
            HandlerRole role, Class<? extends Record> payloadType, byte[] payload) {
        DispatchRequestEnvelope envelope = new DispatchRequestEnvelope(
                role,
                new DispatchCorrelationId(UUID.randomUUID()),
                PeerId.of("pod-sender"),
                payloadType.getName(),
                "java-v1",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                /* tenant */ null,
                System.currentTimeMillis(),
                /* deadlineRemainingMillis */ 30_000L,
                payload);
        return new LocalDispatchHandler.LocalDispatchContext(
                envelope,
                role,
                RingTransportPrincipal.anonymous(net.nexus_flow.core.ring.transport.PeerAddress.loopback(9000)),
                OptionalLong.empty());
    }

    @Test
    void noReturnCommand_routesToFlowRuntime_andReturnsAccepted() {
        CountingCommandHandler handler = new CountingCommandHandler();
        try (FlowRuntime runtime = FlowRuntime.builder().handler(handler).build()) {
            JavaSerializationCommandPayloadCodec codec = new JavaSerializationCommandPayloadCodec();
            byte[]                               body  = codec.encode(new DemoCommand("hello"));

            RuntimeBackedLocalDispatchHandler dispatcher = RuntimeBackedLocalDispatchHandler.builder()
                    .runtime(runtime)
                    .codec(codec)
                    .build();

            DispatchResponseEnvelope response = dispatcher.dispatch(
                                                                    requestFor(HandlerRole.COMMAND, DemoCommand.class, body));

            assertEquals(DispatchResponseEnvelope.Outcome.ACCEPTED, response.outcome(),
                         "no-return commands are fire-and-forget — wire signal is ACCEPTED. "
                                 + "Got " + response.outcome()
                                 + " errorCode=" + response.errorCode()
                                 + " reason=" + response.reason());
            assertEquals(1, handler.calls.get(),
                         "the COMMAND must reach the local FlowRuntime's CommandBus");
            assertEquals("hello", handler.last.get().text());
        }
    }

    @Test
    void returnCommand_routesToFlowRuntime_andReturnsSuccess() {
        CountingReturnHandler handler = new CountingReturnHandler();
        try (FlowRuntime runtime = FlowRuntime.builder().handler(handler).build()) {
            JavaSerializationCommandPayloadCodec codec = new JavaSerializationCommandPayloadCodec();
            byte[]                               body  = codec.encode(new DemoCommand("hello"));

            RuntimeBackedLocalDispatchHandler dispatcher = RuntimeBackedLocalDispatchHandler.builder()
                    .runtime(runtime)
                    .codec(codec)
                    .build();

            DispatchResponseEnvelope response = dispatcher.dispatch(
                                                                    requestFor(HandlerRole.COMMAND, DemoCommand.class, body));

            assertEquals(DispatchResponseEnvelope.Outcome.SUCCESS, response.outcome(),
                         "return commands map handler success to wire SUCCESS. Got "
                                 + response.outcome()
                                 + " errorCode=" + response.errorCode()
                                 + " reason=" + response.reason());
            assertEquals(1, handler.calls.get());
        }
    }

    @Test
    void query_routesToFlowRuntime_andReturnsSuccess() {
        CountingQueryHandler queryHandler = new CountingQueryHandler();
        try (FlowRuntime runtime = FlowRuntime.builder().handler(queryHandler).build()) {
            JavaSerializationCommandPayloadCodec codec = new JavaSerializationCommandPayloadCodec();
            byte[]                               body  = codec.encode(new DemoQuery("42"));

            RuntimeBackedLocalDispatchHandler dispatcher = RuntimeBackedLocalDispatchHandler.builder()
                    .runtime(runtime)
                    .codec(codec)
                    .build();

            DispatchResponseEnvelope response = dispatcher.dispatch(
                                                                    requestFor(HandlerRole.QUERY, DemoQuery.class, body));

            assertEquals(DispatchResponseEnvelope.Outcome.SUCCESS, response.outcome());
            assertEquals(1, queryHandler.calls.get());
        }
    }

    @Test
    void unknownPayloadType_returnsNotFound() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            JavaSerializationCommandPayloadCodec codec      = new JavaSerializationCommandPayloadCodec();
            RuntimeBackedLocalDispatchHandler    dispatcher = RuntimeBackedLocalDispatchHandler.builder()
                    .runtime(runtime)
                    .codec(codec)
                    .build();

            DispatchRequestEnvelope                   envelope = new DispatchRequestEnvelope(
                    HandlerRole.COMMAND,
                    new DispatchCorrelationId(UUID.randomUUID()),
                    PeerId.of("pod-sender"),
                    "com.nonexistent.Type",
                    "java-v1",
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    null,
                    System.currentTimeMillis(),
                    30_000L,
                    new byte[0]);
            LocalDispatchHandler.LocalDispatchContext ctx      = new LocalDispatchHandler.LocalDispatchContext(
                    envelope,
                    HandlerRole.COMMAND,
                    RingTransportPrincipal.anonymous(net.nexus_flow.core.ring.transport.PeerAddress.loopback(9000)),
                    OptionalLong.empty());

            DispatchResponseEnvelope response = dispatcher.dispatch(ctx);
            assertEquals(DispatchResponseEnvelope.Outcome.NOT_FOUND, response.outcome());
        }
    }

    @Test
    void classResolverAllowlist_blocksDisallowedTypes() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            JavaSerializationCommandPayloadCodec codec      = new JavaSerializationCommandPayloadCodec();
            byte[]                               body       = codec.encode(new DemoCommand("hello"));
            RuntimeBackedLocalDispatchHandler    dispatcher = RuntimeBackedLocalDispatchHandler.builder()
                    .runtime(runtime)
                    .codec(codec)
                    .classResolver(RuntimeBackedLocalDispatchHandler.ClassResolver.allowlist(
                                                                                             Set.of("com.allowed.Only")))
                    .build();

            DispatchResponseEnvelope response = dispatcher.dispatch(
                                                                    requestFor(HandlerRole.COMMAND, DemoCommand.class, body));

            assertEquals(DispatchResponseEnvelope.Outcome.NOT_FOUND, response.outcome(),
                         "allowlist rejection MUST surface as NOT_FOUND with no leaked diagnostic");
        }
    }

    @Test
    void expiredDeadline_returnsTimeout_withoutInvokingHandler() {
        CountingCommandHandler handler = new CountingCommandHandler();
        try (FlowRuntime runtime = FlowRuntime.builder().handler(handler).build()) {
            JavaSerializationCommandPayloadCodec codec = new JavaSerializationCommandPayloadCodec();
            byte[]                               body  = codec.encode(new DemoCommand("hello"));

            RuntimeBackedLocalDispatchHandler dispatcher = RuntimeBackedLocalDispatchHandler.builder()
                    .runtime(runtime)
                    .codec(codec)
                    .build();

            DispatchRequestEnvelope envelope = new DispatchRequestEnvelope(
                    HandlerRole.COMMAND,
                    new DispatchCorrelationId(UUID.randomUUID()),
                    PeerId.of("pod-sender"),
                    DemoCommand.class.getName(),
                    "java-v1",
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    null,
                    System.currentTimeMillis(),
                    30_000L,
                    body);
            // Already-expired deadline (1 ns in the past).
            LocalDispatchHandler.LocalDispatchContext ctx = new LocalDispatchHandler.LocalDispatchContext(
                    envelope,
                    HandlerRole.COMMAND,
                    RingTransportPrincipal.anonymous(net.nexus_flow.core.ring.transport.PeerAddress.loopback(9000)),
                    OptionalLong.of(System.nanoTime() - 1L));

            DispatchResponseEnvelope response = dispatcher.dispatch(ctx);
            assertEquals(DispatchResponseEnvelope.Outcome.TIMEOUT, response.outcome());
            assertEquals(0, handler.calls.get(),
                         "an already-expired deadline MUST NOT reach the handler");
        }
    }

    @Test
    void correlationId_isEchoed_unchanged() {
        CountingCommandHandler handler = new CountingCommandHandler();
        try (FlowRuntime runtime = FlowRuntime.builder().handler(handler).build()) {
            JavaSerializationCommandPayloadCodec codec = new JavaSerializationCommandPayloadCodec();
            byte[]                               body  = codec.encode(new DemoCommand("x"));

            RuntimeBackedLocalDispatchHandler dispatcher = RuntimeBackedLocalDispatchHandler.builder()
                    .runtime(runtime)
                    .codec(codec)
                    .build();

            LocalDispatchHandler.LocalDispatchContext ctx      = requestFor(
                                                                            HandlerRole.COMMAND, DemoCommand.class, body);
            DispatchResponseEnvelope                  response = dispatcher.dispatch(ctx);
            assertSame(ctx.request().correlationId(), response.correlationId(),
                       "the wire correlation id MUST be echoed verbatim");
            assertEquals(DispatchResponseEnvelope.Outcome.ACCEPTED, response.outcome());
            assertTrue(handler.calls.get() > 0);
        }
    }
}

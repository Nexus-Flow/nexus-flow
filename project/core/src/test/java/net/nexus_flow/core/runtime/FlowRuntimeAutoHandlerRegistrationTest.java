package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link FlowRuntime.Builder#handler(Object)} / {@link FlowRuntime.Builder#handlers(Object...)}
 * — the DX shortcut that lets callers wire handlers / listeners / query handlers without
 * touching the bus accessors explicitly.
 */
class FlowRuntimeAutoHandlerRegistrationTest {

    record PingCommand(String text) {
    }

    record FetchQuery(String id) {
    }

    static final class PingHandledEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        PingHandledEvent(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class PingHandler extends AbstractNoReturnCommandHandler<PingCommand> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void handle(PingCommand command) {
            calls.incrementAndGet();
        }
    }

    static final class FetchHandler extends AbstractQueryHandler<FetchQuery, String> {
        @Override
        public String handle(FetchQuery query) {
            return "answer:" + query.id();
        }
    }

    static final class PingListener extends AbstractDomainEventListener<PingHandledEvent> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void handle(PingHandledEvent event) {
            calls.incrementAndGet();
        }
    }

    @Test
    void handler_registersCommandHandler_andDispatchReachesIt() {
        PingHandler handler = new PingHandler();
        try (FlowRuntime runtime = FlowRuntime.builder().handler(handler).build()) {
            runtime.commands().dispatch(
                                        Command.<PingCommand>builder().body(new PingCommand("hi")).build());
            assertEquals(1, handler.calls.get(),
                         "auto-registered command handler MUST receive dispatched commands");
        }
    }

    @Test
    void handlers_registersMultipleInOrder_andEachIsReachable() {
        PingHandler  cmd      = new PingHandler();
        FetchHandler query    = new FetchHandler();
        PingListener listener = new PingListener();
        try (FlowRuntime runtime = FlowRuntime.builder()
                .handlers(cmd, query, listener)
                .build()) {
            runtime.commands().dispatch(
                                        Command.<PingCommand>builder().body(new PingCommand("x")).build());
            String answer = runtime.queries().ask(
                                                  Query.<FetchQuery>builder().body(new FetchQuery("42")).build());
            runtime.events().dispatchResult(new PingHandledEvent("agg-1"));

            assertEquals(1, cmd.calls.get());
            assertEquals("answer:42", answer);
            assertEquals(1, listener.calls.get());
        }
    }

    @Test
    void handler_unsupportedType_throwsIllegalArgumentEager() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                                   () -> FlowRuntime.builder().handler("just-a-string"));
        assertTrue(ex.getMessage().contains("Unsupported handler/listener type"),
                   "rejection message must name the problem: " + ex.getMessage());
    }

    @Test
    void handler_null_throwsNPE() {
        assertThrows(NullPointerException.class,
                     () -> FlowRuntime.builder().handler(null));
    }
}

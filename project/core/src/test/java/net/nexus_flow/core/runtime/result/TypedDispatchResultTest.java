package net.nexus_flow.core.runtime.result;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * typed-result methods on {@link net.nexus_flow.core.cqrs.command.CommandBus} and {@link EventBus}.
 *
 * <p>The legacy {@code dispatch}/{@code dispatchAndReturn} keep their existing contract (covered
 * elsewhere); these tests only exercise the additive {@code dispatchAndReturnResult} / {@code
 * dispatchResult} methods.
 */
class TypedDispatchResultTest {

    record Greet(String name) {
    }

    static final class GreetingEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        final String              name;

        GreetingEvent(String name) {
            super("greet:" + name);
            this.name = name;
        }
    }

    @Test
    void commandBus_dispatchAndReturnResult_returnsSuccessForHappyPath() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            return "hi:" + command.name();
                        }
                    };
            runtime.commands().register(handler);
            try {
                Command<Greet>         cmd = Command.<Greet>builder().body(new Greet("nexus")).build();
                DispatchResult<String> r   = runtime.commands().dispatchAndReturnResult(cmd);
                // pattern-matching `instanceof` so the
                // narrowed Success<String> is accessible without a cast,
                // mirroring the style of DispatchResultPatternMatchingTest.
                if (!(r instanceof DispatchResult.Success<String> ok)) {
                    throw new AssertionError("Expected Success, got " + r);
                }
                assertEquals("hi:nexus", ok.value());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    @Test
    void commandBus_dispatchAndReturnResult_unregisteredCommand_returnsFailure() {
        record Stranger(String x) {
        }
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            Command<Stranger>      cmd = Command.<Stranger>builder().body(new Stranger("?")).build();
            DispatchResult<Object> r   = runtime.commands().dispatchAndReturnResult(cmd);
            if (!(r instanceof DispatchResult.Failure<Object> failure)) {
                throw new AssertionError("Expected Failure, got " + r);
            }
            // Cause is the typed CommandNotRegisteredError, no wrapping.
            assertNotNull(failure.cause());
        }
    }

    @Test
    void eventBus_dispatchResult_emptyListeners_isSuccess() {
        class LonelyEvent extends AbstractDomainEvent {
            @Serial
            private static final long serialVersionUID = 1L;

            LonelyEvent(String aggId) {
                super(aggId);
            }
        }
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            DispatchResult<Void> r = runtime.events().dispatchResult(new LonelyEvent("agg"));
            assertInstanceOf(DispatchResult.Success.class, r);
        }
    }

    @Test
    void eventBus_dispatchResult_invokesEveryListener() {
        // the EventBus is per-runtime; obtain it via the
        // runtime accessor instead of the removed EventBus.getInstance().
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            EventBus                bus = runtime.events();
            AtomicReference<String> a   = new AtomicReference<>();
            AtomicReference<String> b   = new AtomicReference<>();
            var                     lA  =
                    new AbstractDomainEventListener<GreetingEvent>() {
                                                    @Override
                                                    public void handle(GreetingEvent event) {
                                                        a.set("A:" + event.name);
                                                    }
                                                };
            var                     lB  =
                    new AbstractDomainEventListener<GreetingEvent>() {
                                                    @Override
                                                    public void handle(GreetingEvent event) {
                                                        b.set("B:" + event.name);
                                                    }
                                                };
            bus.register(lA);
            bus.register(lB);
            try {
                DispatchResult<Void> r = bus.dispatchResult(new GreetingEvent("nexus"));
                assertInstanceOf(DispatchResult.Success.class, r);
                assertEquals("A:nexus", a.get());
                assertEquals("B:nexus", b.get());
            } finally {
                bus.unregister(lA);
                bus.unregister(lB);
            }
        }
    }

    @Test
    void commandBus_legacyDispatchAndReturn_stillWorks_unchanged() {
        // The additive dispatchAndReturnResult method must not change the
        // behaviour of the legacy dispatchAndReturn method.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            return "legacy:" + command.name();
                        }
                    };
            runtime.commands().register(handler);
            try {
                Command<Greet> cmd    = Command.<Greet>builder().body(new Greet("x")).build();
                String         result = runtime.commands().dispatchAndReturn(cmd);
                assertEquals("legacy:x", result);
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }
}

package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.types.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Validates the ergonomic {@code CommandHandler.forCommand(...)} DSL and the fail-fast on broken
 * generic capture ({@code .of(Function)} delegating to anonymous-subclass diamond).
 */
class CommandHandlerDslTest {

    record Greet(String name) {
    }

    record Tick(int n) {
    }

    record FetchOrders(String customerId) {
    }

    record OrderId(String value) {
    }

    @Nested
    @DisplayName("forCommand(Class) — no-return flavour")
    class NoReturnFlow {

        @Test
        void handle_buildsTypeAwareHandler_thatRoutesEndToEnd() {
            try (FlowRuntime runtime = FlowRuntime.builder().build()) {
                AtomicInteger counter = new AtomicInteger();
                var           handler =
                        CommandHandler.forCommand(Tick.class).handle(cmd -> counter.addAndGet(cmd.n()));

                assertInstanceOf(CommandTypeAware.class, handler);
                assertEquals(Tick.class, ((CommandTypeAware<?>) handler).getCommandType().getType());

                runtime.commands().register(handler);
                try {
                    runtime.commands().dispatch(Command.<Tick>builder().body(new Tick(4)).build());
                    runtime.commands().dispatch(Command.<Tick>builder().body(new Tick(6)).build());

                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (counter.get() < 10 && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    assertEquals(10, counter.get());
                } finally {
                    runtime.commands().unregister(handler);
                }
            }
        }

        @Test
        void handle_rejectsNullConsumer() {
            assertThrows(
                         NullPointerException.class, () -> CommandHandler.forCommand(Greet.class).handle(null));
        }
    }

    @Nested
    @DisplayName("forCommand(Class).returns(Class) — return flavour")
    class ReturnFlow {

        @Test
        void handle_buildsTypeAwareHandler_thatRoutesEndToEnd() {
            try (FlowRuntime runtime = FlowRuntime.builder().build()) {
                var handler =
                        CommandHandler.forCommand(Greet.class)
                                .returns(String.class)
                                .handle(cmd -> "hello " + cmd.name());

                assertInstanceOf(CommandTypeAware.class, handler);
                assertEquals(Greet.class, ((CommandTypeAware<?>) handler).getCommandType().getType());

                runtime.commands().register(handler);
                try {
                    String response =
                            runtime
                                    .commands()
                                    .dispatchAndReturn(Command.<Greet>builder().body(new Greet("Ada")).build());
                    assertEquals("hello Ada", response);
                } finally {
                    runtime.commands().unregister(handler);
                }
            }
        }

        @Test
        void returns_acceptsTypeReference_forParameterisedResponses() {
            var handler =
                    CommandHandler.forCommand(FetchOrders.class)
                            .returns(new TypeReference<List<OrderId>>() {
                            })
                            .handle(
                                    cmd -> List.of(
                                                   new OrderId(cmd.customerId() + "#1"),
                                                   new OrderId(cmd.customerId() + "#2")));

            assertInstanceOf(CommandTypeAware.class, handler);
            assertEquals(FetchOrders.class, ((CommandTypeAware<?>) handler).getCommandType().getType());

            // Exercise the inner directly — the bus wraps the call in
            // its own context which we don't need here.
            var internal = (ReturnCommandHandlerInternal<FetchOrders, List<OrderId>>) handler;
            try {
                List<OrderId> result = internal.handleAndReturn(new FetchOrders("C42")).call();
                assertEquals(2, result.size());
                assertEquals("C42#1", result.getFirst().value());
            } catch (Exception e) {
                fail(e);
            }
        }

        @Test
        void handle_rejectsNullFunction() {
            assertThrows(
                         NullPointerException.class,
                         () -> CommandHandler.forCommand(Greet.class).returns(String.class).handle(null));
        }

        @Test
        void returns_rejectsNullClass() {
            assertThrows(
                         NullPointerException.class,
                         () -> CommandHandler.forCommand(Greet.class).returns((Class<?>) null));
        }

        @Test
        void returns_rejectsNullTypeReference() {
            assertThrows(
                         NullPointerException.class,
                         () -> CommandHandler.forCommand(Greet.class).returns((TypeReference<?>) null));
        }
    }

    @Nested
    @DisplayName("Entry-point invariants")
    class EntryPoint {

        @Test
        void forCommand_rejectsNullClass() {
            assertThrows(NullPointerException.class, () -> CommandHandler.forCommand(null));
        }
    }

    @Nested
    @DisplayName("fail-fast on broken generic capture")
    class FailFast {

        @Test
        void commandTypeSignature_rejectsUnresolvedTypeVariables() {
            // Simulates a generic static factory that captures its own
            // method-scoped <T,R> through diamond + anonymous subclass.
            // The base class must detect the TypeVariable and refuse to
            // construct, naming the working API.
            class GenericFactory {
                <T extends Record, R> CommandTypeSignature<T, R> brokenCapture() {
                    return new CommandTypeSignature<>() {
                    };
                }
            }
            IllegalStateException ex =
                    assertThrows(
                                 IllegalStateException.class,
                                 () -> new GenericFactory().<Greet, String>brokenCapture());
            assertTrue(
                       ex.getMessage().contains("forCommand"),
                       "Error message must point to the typed DSL, got: " + ex.getMessage());
            assertTrue(
                       ex.getMessage().contains("type variable"),
                       "Error message must name the failure mode, got: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Sealed DSL contract")
    class SealedSurface {

        @Test
        void stepInterfaces_areSealed_toThePackagePrivateImpls() {
            // Compile-time guarantee that nobody can implement CommandStep
            // or ResponseStep from outside this package. Verified
            // dynamically via the sealed-class introspection API.
            assertTrue(CommandHandlerDsl.CommandStep.class.isSealed(), "CommandStep must be sealed");
            assertTrue(CommandHandlerDsl.ResponseStep.class.isSealed(), "ResponseStep must be sealed");
        }

        @Test
        void dsl_isNotInstantiable() {
            // Defensive: the DSL is a static utility; the no-instances
            // contract is enforced by an AssertionError-throwing
            // constructor we reach through reflection.
            assertThrows(
                         Exception.class,
                         () -> {
                             var ctor = CommandHandlerDsl.class.getDeclaredConstructor();
                             ctor.setAccessible(true);
                             ctor.newInstance();
                         });
        }
    }

    /** Hold on to an event reference so the closure isn't elided. */
    @SuppressWarnings("unused")
    private static final AtomicReference<Object> REACHABILITY_PIN = new AtomicReference<>();
}

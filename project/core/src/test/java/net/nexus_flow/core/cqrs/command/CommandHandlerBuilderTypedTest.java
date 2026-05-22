package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Validates the {@link CommandTypeSignature}-based fluent builder API for {@link CommandHandler}
 * (Option A — super-type token).
 *
 * <p>The test surface covers three concerns:
 *
 * <ul>
 * <li>Compile-time inference: passing a {@code new CommandTypeSignature<X, Y>() {}} must let the
 * lambda parameter type be inferred as {@code X} (verified by the code itself compiling
 * without explicit casts).
 * <li>Runtime type recovery: the resulting handler must expose the captured {@link
 * net.nexus_flow.core.types.TypeReference} so {@link
 * CommandBus#register(NoReturnCommandHandler)} can route to it.
 * <li>End-to-end dispatch: a builder-produced handler must behave identically to an {@link
 * AbstractReturnCommandHandler} / {@link AbstractNoReturnCommandHandler} subclass.
 * </ul>
 */
class CommandHandlerBuilderTypedTest {

    record Greet(String name) {
    }

    record Tick(int n) {
    }

    @Nested
    @DisplayName("Return-value builder")
    class ReturnFlavor {

        @Test
        void build_producesHandler_thatRunsTheLambda_andCapturesType() {
            var handler =
                    CommandHandler.builder(new CommandTypeSignature<Greet, String>() {
                    })
                            .withHandleFunctionResponse(cmd -> "hi " + cmd.name()) // cmd inferred as Greet
                            .withPriority(7)
                            .withConcurrencyLevel(2)
                            .build();

            assertEquals(7, handler.getPriority());
            assertEquals(2, handler.getConcurrencyLevel());
            assertInstanceOf(
                             CommandTypeAware.class,
                             handler,
                             "builder-produced handlers must carry their command type");
            assertEquals(Greet.class, ((CommandTypeAware<?>) handler).getCommandType().getType());
        }

        @Test
        void register_andDispatchAndReturn_routesThroughTheBuilderHandler() {
            try (FlowRuntime runtime = FlowRuntime.builder().build()) {
                var handler =
                        CommandHandler.builder(new CommandTypeSignature<Greet, String>() {
                        })
                                .withHandleFunctionResponse(cmd -> "hi " + cmd.name())
                                .build();
                runtime.commands().register(handler);
                try {
                    Command<Greet> cmd = Command.<Greet>builder().body(new Greet("Ada")).build();
                    assertEquals("hi Ada", runtime.commands().<Greet, String>dispatchAndReturn(cmd));
                } finally {
                    runtime.commands().unregister(handler);
                }
            }
        }

        @Test
        void build_withoutResponseFunction_failsFast() {
            var                   step =
                    CommandHandler.builder(new CommandTypeSignature<Greet, String>() {
                                               })
                            .withHandleFunctionResponse(null);
            IllegalStateException ex   = assertThrows(IllegalStateException.class, step::build);
            assertTrue(
                       ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("response"),
                       "Error message must name the missing piece, got: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("No-return builder")
    class NoReturnFlavor {

        @Test
        void build_producesHandler_thatRunsTheConsumer_andCapturesType() {
            AtomicReference<String> sideEffect = new AtomicReference<>();
            var                     handler    =
                    CommandHandler.builderNoReturn(new CommandTypeSignature<Greet, Void>() {
                                                       })
                            .withHandleFunction(cmd -> sideEffect.set(cmd.name())) // cmd inferred as Greet
                            .build();

            assertInstanceOf(CommandTypeAware.class, handler);
            assertEquals(Greet.class, ((CommandTypeAware<?>) handler).getCommandType().getType());

            // Exercise the handler directly through its Internal facet.
            ((NoReturnCommandHandlerInternal<Greet>) handler).handle(new Greet("Linus")).run();
            assertEquals("Linus", sideEffect.get());
        }

        @Test
        void register_andDispatch_routesThroughTheBuilderHandler() {
            try (FlowRuntime runtime = FlowRuntime.builder().build()) {
                AtomicInteger counter = new AtomicInteger();
                var           handler =
                        CommandHandler.builderNoReturn(new CommandTypeSignature<Tick, Void>() {
                                              })
                                .withHandleFunction(cmd -> counter.addAndGet(cmd.n()))
                                .build();
                runtime.commands().register(handler);
                try {
                    Command<Tick> cmd = Command.<Tick>builder().body(new Tick(5)).build();
                    runtime.commands().dispatch(cmd);
                    Command<Tick> cmd2 = Command.<Tick>builder().body(new Tick(3)).build();
                    runtime.commands().dispatch(cmd2);

                    // Allow the asynchronous executor to drain.
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                    while (counter.get() < 8 && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    assertEquals(
                                 8, counter.get(), "both commands must have been delivered to the builder handler");
                } finally {
                    runtime.commands().unregister(handler);
                }
            }
        }
    }

    @Nested
    @DisplayName("Super-type token contract")
    class TokenContract {

        @Test
        void instantiating_withoutAnonymousSubclass_failsFast() {
            // Constructing the raw type directly (no anonymous subclass)
            // must fail with a descriptive error so callers learn how to
            // use the API at 3am.
            //
            // We bypass the protected constructor via reflection because
            // CommandTypeSignature is abstract, but the error path we
            // care about is the IllegalStateException thrown inside the
            // constructor itself when getGenericSuperclass() isn't a
            // ParameterizedType. The easiest way to provoke that
            // condition is to subclass it raw (no type arguments).
            @SuppressWarnings("rawtypes")
            class Raw extends CommandTypeSignature {
            }
            IllegalStateException ex = assertThrows(IllegalStateException.class, Raw::new);
            assertTrue(
                       ex.getMessage().contains("anonymous subclass"),
                       "Error must guide the caller, got: " + ex.getMessage());
        }

        @Test
        void factories_rejectNullSignature() {
            assertThrows(NullPointerException.class, () -> CommandHandler.builder(null));
            assertThrows(NullPointerException.class, () -> CommandHandler.builderNoReturn(null));
        }
    }
}

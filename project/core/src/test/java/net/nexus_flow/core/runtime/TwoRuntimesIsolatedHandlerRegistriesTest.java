package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.exceptions.CommandNotRegisteredError;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * two {@link FlowRuntime} instances must own independent handler registries.
 *
 * <p>The pre-2.5 {@code CommandBus.getInstance()} singleton caused handlers registered in one
 * runtime to silently service dispatches issued by an unrelated second runtime. Aftereach runtime
 * owns its own {@code CommandConsumerRegistry}; a command type that exists only in runtime A must
 * surface {@link CommandNotRegisteredError} when dispatched on runtime B, and closing one runtime
 * must not silently re-route subsequent dispatches to the other.
 */
class TwoRuntimesIsolatedHandlerRegistriesTest {

    record Ping(String tag) {
    }

    @Test
    void handler_registeredInRuntimeA_isNotVisibleFromRuntimeB() {
        try (FlowRuntime a = FlowRuntime.builder().build();
                FlowRuntime b = FlowRuntime.builder().build()) {

            // Sanity: two distinct bus instances — no singleton aliasing.
            assertNotSame(a.commands(), b.commands(), " each runtime must expose its own CommandBus");

            var handler =
                    new AbstractReturnCommandHandler<Ping, String>() {
                        @Override
                        protected String handle(Ping command) {
                            return "A:" + command.tag();
                        }
                    };
            a.commands().register(handler);

            // Runtime A resolves the handler — establishes the baseline.
            DispatchResult<String> hit =
                    a.commands()
                            .dispatchAndReturnResult(Command.<Ping>builder().body(new Ping("hello")).build());
            var                    ok  = assertInstanceOf(DispatchResult.Success.class, hit);
            assertEquals("A:hello", ok.value());

            // Runtime B has NEVER seen the handler — must report a
            // typed CommandNotRegisteredError (not a generic Failure
            // wrapping a NullPointerException).
            DispatchResult<String> miss    =
                    b.commands()
                            .dispatchAndReturnResult(Command.<Ping>builder().body(new Ping("hello")).build());
            var                    failure = assertInstanceOf(DispatchResult.Failure.class, miss);
            assertInstanceOf(
                             CommandNotRegisteredError.class,
                             failure.cause(),
                             " runtime B must NOT inherit handlers from runtime A");
        }
    }

    @Test
    void closingOneRuntime_doesNotPoisonHandlersOfTheOther() {
        // Symmetric guard: closing runtime A must not yank handlers
        // registered on runtime B — the buses must be fully independent.
        FlowRuntime a = FlowRuntime.builder().build();
        try (FlowRuntime b = FlowRuntime.builder().build()) {
            var hb =
                    new AbstractReturnCommandHandler<Ping, String>() {
                        @Override
                        protected String handle(Ping command) {
                            return "B:" + command.tag();
                        }
                    };
            b.commands().register(hb);
            // Drain runtime A by closing it BEFORE B issues its dispatch.
            a.close();

            DispatchResult<String> r  =
                    b.commands()
                            .dispatchAndReturnResult(Command.<Ping>builder().body(new Ping("ok")).build());
            var                    ok = assertInstanceOf(DispatchResult.Success.class, r);
            assertEquals("B:ok", ok.value(), " closing runtime A must not affect runtime B's handlers");
        }
    }
}

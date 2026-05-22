package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.CommandSettings;
import net.nexus_flow.core.cqrs.command.ReturnCommandHandler;
import org.junit.jupiter.api.Test;

/**
 * Observable carrier-thread jump driven by the per-handler {@link CommandSettings#executionMode()}
 * override.
 *
 * <p>Earlier revisions left {@link CommandSettings#executionMode()} inert: the legacy executor
 * forced {@link ExecutionStrategy.Inline} regardless of the declared mode, so a handler asking for
 * {@link ExecutionMode#asynchronousInMemory()} silently kept running on the caller. The current
 * code routes the decision through {@link ExecutionStrategyResolver}, which means a handler
 * override now produces an observable thread hop.
 *
 * <p>The two tests below pin both directions of the override matrix:
 *
 * <ul>
 * <li>handler={@code AsynchronousInMemory} under runtime={@code Synchronous} ⇒ task runs on a
 * runtime VT carrier (NOT the caller),
 * <li>handler={@code Synchronous} under runtime={@code AsynchronousInMemory} ⇒ task runs inline
 * on the caller despite the runtime fan-out default.
 * </ul>
 *
 * <p>The check is intentionally thread-identity-based rather than "is virtual" — it is the
 * strongest available proof that the resolver picked the right strategy without relying on internal
 * state of {@link ExecutionStrategy.VirtualThread}.
 */
class HandlerLevelStrategyOverrideTest {

    record Ping(String id) {
    }

    /**
     * Handler whose {@link CommandSettings} carries an explicit {@link ExecutionMode} override, used
     * to drive both directions of the matrix. The body records the executing thread so the test can
     * compare it against the caller.
     */
    private static ReturnCommandHandler<Ping, Thread> handlerWithOverride(ExecutionMode override) {
        CommandSettings settings = CommandSettings.builder().executionMode(override).build();
        return new AbstractReturnCommandHandler<>() {
            @Override
            protected Thread handle(Ping command) {
                return Thread.currentThread();
            }

            @Override
            public CommandSettings getCommandSettings() {
                return settings;
            }
        };
    }

    // ------------------------------------------------------------------
    // Direction 1 — handler override forces VT under Synchronous runtime.
    // ------------------------------------------------------------------

    @Test
    void handlerAsyncInMemoryOverride_underSynchronousRuntime_jumpsToVtCarrier() {
        try (FlowRuntime runtime =
                FlowRuntime.builder().defaultExecutionMode(ExecutionMode.synchronous()).build()) {
            ReturnCommandHandler<Ping, Thread> handler =
                    handlerWithOverride(ExecutionMode.asynchronousInMemory());
            runtime.commands().register(handler);
            try {
                Thread        caller    = Thread.currentThread();
                Command<Ping> cmd       = Command.<Ping>builder().body(new Ping("x")).build();
                Thread        executing = runtime.commands().dispatchAndReturn(cmd);
                assertNotNull(executing, "Handler must have executed and returned its carrier thread");
                assertNotEquals(
                                caller,
                                executing,
                                " handler override AsynchronousInMemory must hop off "
                                        + "the caller thread even when the runtime default is Synchronous. "
                                        + "Got caller="
                                        + caller
                                        + ", executing="
                                        + executing);
                assertTrue(
                           executing.isVirtual(),
                           " the carrier picked by the resolver must be a virtual thread "
                                   + "owned by the runtime VT executor; got platform thread "
                                   + executing);
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    // ------------------------------------------------------------------
    // Direction 2 — handler override forces Inline under VT runtime.
    // ------------------------------------------------------------------

    @Test
    void handlerSynchronousOverride_underAsyncInMemoryRuntime_runsInlineOnCaller() {
        try (FlowRuntime runtime =
                FlowRuntime.builder().defaultExecutionMode(ExecutionMode.asynchronousInMemory()).build()) {
            ReturnCommandHandler<Ping, Thread> handler = handlerWithOverride(ExecutionMode.synchronous());
            runtime.commands().register(handler);
            try {
                Thread        caller    = Thread.currentThread();
                Command<Ping> cmd       = Command.<Ping>builder().body(new Ping("x")).build();
                Thread        executing = runtime.commands().dispatchAndReturn(cmd);
                assertSame(
                           caller,
                           executing,
                           " handler override Synchronous must keep the task on the "
                                   + "caller thread even when the runtime default is AsynchronousInMemory. "
                                   + "Got caller="
                                   + caller
                                   + ", executing="
                                   + executing);
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    // ------------------------------------------------------------------
    // Cross-check — sanity baseline: no override, runtime Synchronous,
    // saga=false ⇒ Inline on the caller. Guards against the resolver
    // accidentally swapping precedence.
    // ------------------------------------------------------------------

    @Test
    void noOverride_synchronousRuntime_keepsHandlerInline() {
        try (FlowRuntime runtime =
                FlowRuntime.builder().defaultExecutionMode(ExecutionMode.synchronous()).build()) {
            AtomicReference<Thread>            seen    = new AtomicReference<>();
            ReturnCommandHandler<Ping, Thread> handler =
                    new AbstractReturnCommandHandler<>() {
                                                                   @Override
                                                                   protected Thread handle(Ping command) {
                                                                       Thread t = Thread.currentThread();
                                                                       seen.set(t);
                                                                       return t;
                                                                   }
                                                               };
            runtime.commands().register(handler);
            try {
                Thread        caller    = Thread.currentThread();
                Command<Ping> cmd       = Command.<Ping>builder().body(new Ping("y")).build();
                Thread        executing = runtime.commands().dispatchAndReturn(cmd);
                assertSame(
                           caller,
                           executing,
                           "Baseline: with no override, runtime=Synchronous and saga=false "
                                   + "must keep the handler inline on the caller.");
                assertSame(caller, seen.get());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }
}

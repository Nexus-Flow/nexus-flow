package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.CommandSettings;
import net.nexus_flow.core.cqrs.command.NoReturnCommandHandler;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxConfig;
import org.junit.jupiter.api.Test;

/**
 * exhaustive matrix test for {@link
 * ExecutionStrategyResolver#resolveStrategy(net.nexus_flow.core.cqrs.command.CommandHandler,
 * FlowRuntime)}.
 *
 * <p>The test fixture varies three dimensions independently:
 *
 * <ul>
 * <li>{@code settings.executionMode}: {@code null} (absent), {@link ExecutionMode#synchronous()},
 * {@link ExecutionMode#asynchronousInMemory()}
 * <li>{@code handler.isSagaEnabled()}: {@code true} / {@code false}
 * <li>{@code runtime.executionMode()}: {@link ExecutionMode#synchronous()} / {@link
 * ExecutionMode#asynchronousInMemory()}
 * </ul>
 *
 * <p>The 12 relevant combinations are enumerated as individual tests so the failing row is obvious
 * in CI output (a parameterised test would collapse them into a single arrow).
 */
class ExecutionStrategyResolverTest {

    record Ping(String id) {
    }

    // ---- Fixtures --------------------------------------------------

    /** Handler with explicit saga flag and optional executionMode override. */
    private static NoReturnCommandHandler<Ping> handler(boolean saga, ExecutionMode override) {
        CommandSettings settings =
                override == null ? new CommandSettings() : CommandSettings.builder().executionMode(override).build();
        return new AbstractNoReturnCommandHandler<>() {
            @Override
            protected void handle(Ping command) {
                /* unused */
            }

            @Override
            public boolean isSagaEnabled() {
                return saga;
            }

            @Override
            public CommandSettings getCommandSettings() {
                return settings;
            }
        };
    }

    private static FlowRuntime runtime(ExecutionMode mode) {
        return FlowRuntime.builder().defaultExecutionMode(mode).build();
    }

    // ----------------------------------------------------------------
    // Rule 1 — settings.executionMode() OVERRIDES everything else.
    // ----------------------------------------------------------------

    @Test
    void override_Synchronous_wins_even_when_runtime_is_AsyncInMemory_and_saga_false() {
        try (FlowRuntime rt = runtime(ExecutionMode.asynchronousInMemory())) {
            ExecutionStrategy s =
                    ExecutionStrategyResolver.resolveStrategy(
                                                              handler(false, ExecutionMode.synchronous()), rt);
            assertInstanceOf(
                             ExecutionStrategy.Inline.class,
                             s,
                             "Explicit handler override (Synchronous) must beat runtime AsyncInMemory.");
        }
    }

    @Test
    void override_Synchronous_wins_even_when_saga_true_and_runtime_AsyncInMemory() {
        try (FlowRuntime rt = runtime(ExecutionMode.asynchronousInMemory())) {
            ExecutionStrategy s =
                    ExecutionStrategyResolver.resolveStrategy(handler(true, ExecutionMode.synchronous()), rt);
            assertInstanceOf(ExecutionStrategy.Inline.class, s);
        }
    }

    @Test
    void override_AsyncInMemory_wins_even_when_saga_true_and_runtime_Synchronous() {
        try (FlowRuntime rt = runtime(ExecutionMode.synchronous())) {
            ExecutionStrategy               s  =
                    ExecutionStrategyResolver.resolveStrategy(
                                                              handler(true, ExecutionMode.asynchronousInMemory()), rt);
            ExecutionStrategy.VirtualThread vt =
                    assertInstanceOf(
                                     ExecutionStrategy.VirtualThread.class,
                                     s,
                                     "Explicit AsyncInMemory override must beat saga=true.");
            assertSame(rt.executor(), vt.executor(), "Resolver must wire the runtime-owned VT executor.");
        }
    }

    @Test
    void override_AsyncInMemory_wins_even_when_saga_false_and_runtime_Synchronous() {
        try (FlowRuntime rt = runtime(ExecutionMode.synchronous())) {
            ExecutionStrategy s =
                    ExecutionStrategyResolver.resolveStrategy(
                                                              handler(false, ExecutionMode.asynchronousInMemory()), rt);
            assertInstanceOf(ExecutionStrategy.VirtualThread.class, s);
        }
    }

    @Test
    void override_AsyncInMemory_wins_even_when_saga_true_and_runtime_AsyncInMemory() {
        try (FlowRuntime rt = runtime(ExecutionMode.asynchronousInMemory())) {
            ExecutionStrategy s =
                    ExecutionStrategyResolver.resolveStrategy(
                                                              handler(true, ExecutionMode.asynchronousInMemory()), rt);
            assertInstanceOf(ExecutionStrategy.VirtualThread.class, s);
        }
    }

    @Test
    void override_Synchronous_wins_even_when_saga_false_and_runtime_Synchronous() {
        try (FlowRuntime rt = runtime(ExecutionMode.synchronous())) {
            ExecutionStrategy s =
                    ExecutionStrategyResolver.resolveStrategy(
                                                              handler(false, ExecutionMode.synchronous()), rt);
            assertInstanceOf(ExecutionStrategy.Inline.class, s);
        }
    }

    // ----------------------------------------------------------------
    // Rule 2 — no override, saga=true ⇒ Synchronous (legacy shortcut).
    // ----------------------------------------------------------------

    @Test
    void noOverride_sagaTrue_runtimeSynchronous_picksInline() {
        try (FlowRuntime rt = runtime(ExecutionMode.synchronous())) {
            ExecutionStrategy s = ExecutionStrategyResolver.resolveStrategy(handler(true, null), rt);
            assertInstanceOf(ExecutionStrategy.Inline.class, s);
        }
    }

    @Test
    void noOverride_sagaTrue_runtimeAsyncInMemory_stillPicksInline() {
        // The saga shortcut maps to Synchronous regardless of the
        // runtime default — preserves the"saga runs inline"
        // guarantee even when the rest of the runtime fans out.
        try (FlowRuntime rt = runtime(ExecutionMode.asynchronousInMemory())) {
            ExecutionStrategy s = ExecutionStrategyResolver.resolveStrategy(handler(true, null), rt);
            assertInstanceOf(ExecutionStrategy.Inline.class, s);
        }
    }

    // ----------------------------------------------------------------
    // Rule 3 — no override, saga=false ⇒ runtime default.
    // ----------------------------------------------------------------

    @Test
    void noOverride_sagaFalse_runtimeSynchronous_picksInline() {
        try (FlowRuntime rt = runtime(ExecutionMode.synchronous())) {
            ExecutionStrategy s = ExecutionStrategyResolver.resolveStrategy(handler(false, null), rt);
            assertInstanceOf(ExecutionStrategy.Inline.class, s);
        }
    }

    @Test
    void noOverride_sagaFalse_runtimeAsyncInMemory_picksVirtualThread() {
        try (FlowRuntime rt = runtime(ExecutionMode.asynchronousInMemory())) {
            ExecutionStrategy               s  = ExecutionStrategyResolver.resolveStrategy(handler(false, null), rt);
            ExecutionStrategy.VirtualThread vt =
                    assertInstanceOf(ExecutionStrategy.VirtualThread.class, s);
            assertSame(
                       rt.executor(), vt.executor(), "Fallback path must wire the runtime-owned VT executor.");
        }
    }

    // ----------------------------------------------------------------
    // Defensive: null arguments.
    // ----------------------------------------------------------------

    @Test
    void rejectsNullHandler() {
        try (FlowRuntime rt = runtime(ExecutionMode.synchronous())) {
            assertThrows(
                         NullPointerException.class, () -> ExecutionStrategyResolver.resolveStrategy(null, rt));
        }
    }

    @Test
    void rejectsNullRuntime() {
        assertThrows(
                     NullPointerException.class,
                     () -> ExecutionStrategyResolver.resolveStrategy(handler(false, null), null));
    }

    // ----------------------------------------------------------------
    // closeout — AsynchronousDurable contract.
    //
    // The resolver is the single point that enforces the durable
    // binding rule: durable mode REQUIRES an outbox on the runtime.
    // When the precondition holds, the resolver returns a
    // {@link ExecutionStrategy.AsynchronousDurable} (Inline-equivalent
    // execution; durability is provided by the per-runtime outbox
    // sink during the post-handler event drain).
    // When the precondition fails, the resolver throws
    // {@link IllegalStateException} with a self-describing message
    // naming the missing builder call.
    // ----------------------------------------------------------------

    private static NoReturnCommandHandler<Ping> durableHandler() {
        CommandSettings settings =
                CommandSettings.builder().executionMode(ExecutionMode.asynchronousDurable()).build();
        return new AbstractNoReturnCommandHandler<>() {
            @Override
            protected void handle(Ping command) {
                /* unused */
            }

            @Override
            public CommandSettings getCommandSettings() {
                return settings;
            }
        };
    }

    private static OutboxConfig newOutboxConfig() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);
        return OutboxConfig.builder(
                                    new InMemoryOutboxStorage(clock), new JavaSerializationOutboxPayloadCodec())
                .clock(clock)
                .autoStartWorker(false)
                .build();
    }

    @Test
    void durableMode_withOutboxBound_resolvesToDurableStrategy() {
        try (FlowRuntime rt = FlowRuntime.builder().outbox(newOutboxConfig()).build()) {
            ExecutionStrategy strategy = ExecutionStrategyResolver.resolveStrategy(durableHandler(), rt);
            assertInstanceOf(
                             ExecutionStrategy.AsynchronousDurable.class,
                             strategy,
                             "Durable mode + outbox bound must resolve to AsynchronousDurable.");
        }
    }

    @Test
    void durableMode_withoutOutbox_throwsIllegalStateException_atResolveTime() {
        try (FlowRuntime rt = FlowRuntime.builder().build()) {
            IllegalStateException ex =
                    assertThrows(
                                 IllegalStateException.class,
                                 () -> ExecutionStrategyResolver.resolveStrategy(durableHandler(), rt));
            // Message must name the missing builder call so the
            // caller can act without consulting the source.
            String msg = ex.getMessage();
            assertTrue(msg.contains("outbox"), "Failure message must mention 'outbox': " + msg);
            assertTrue(
                       msg.contains("AsynchronousDurable"),
                       "Failure message must name the offending mode: " + msg);
            assertTrue(
                       msg.contains("FlowRuntime") || msg.contains("builder"),
                       "Failure message should point at the missing builder call: " + msg);
        }
    }

    @Test
    void durableMode_asRuntimeDefault_withoutOutbox_alsoFailsAtResolveTime() {
        // The runtime-level default is also the durable mode → the
        // resolver still has to refuse since no outbox is bound. This
        // pins the contract at the "fallback default" precedence
        // branch as well as the "explicit override" branch above.
        try (FlowRuntime rt =
                FlowRuntime.builder().defaultExecutionMode(ExecutionMode.asynchronousDurable()).build()) {
            NoReturnCommandHandler<Ping> handlerWithoutOverride = handler(false, null);
            assertThrows(
                         IllegalStateException.class,
                         () -> ExecutionStrategyResolver.resolveStrategy(handlerWithoutOverride, rt));
        }
    }
}

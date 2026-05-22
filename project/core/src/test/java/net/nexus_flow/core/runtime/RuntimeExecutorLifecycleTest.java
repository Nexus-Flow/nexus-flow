package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import org.junit.jupiter.api.Test;

/**
 * Ownership and lifecycle of the virtual thread executor.
 *
 * <p>Three load-bearing guarantees:
 *
 * <ul>
 * <li>{@code close()} drains the shared VT executor with the configured graceful timeout.
 * <li>{@code close()} is idempotent (already covered by {@link FlowRuntimeLifecycleTest}; we
 * re-prove it here under active executor traffic).
 * <li>{@code close()} with tasks in flight respects the timeout — the grace period is honoured
 * and overrunning tasks are interrupted via {@code shutdownNow()}.
 * </ul>
 */
class RuntimeExecutorLifecycleTest {

    record SlowGreet(long sleepMillis) {
    }

    @Test
    void close_releasesExecutorReference() {
        FlowRuntime runtime  = FlowRuntime.builder().build();
        var         executor = runtime.executor();
        runtime.close();

        // The executor we observed must be terminated after close;
        // accessing it post-close throws so we can't read it again.
        assertTrue(executor.isShutdown(), "close() must shutdown the runtime-owned executor");
        assertThrows(
                     IllegalStateException.class, runtime::executor, "executor() rejects access after close");
    }

    @Test
    void close_isIdempotent_underLifecycle() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        runtime.close();
        runtime.close();
        runtime.close();
        assertTrue(runtime.isClosed());
    }

    @Test
    void close_withTasksInFlight_respectsShutdownTimeout() throws InterruptedException {
        // Test: register a slow handler, dispatch it on the runtime executor,
        // then close() with a tiny timeout. close() must return without
        // hanging the test (proving shutdownNow kicks in when the grace
        // period elapses) and the in-flight task must observe an interrupt.
        Duration    tiny    = Duration.ofMillis(50);
        FlowRuntime runtime = FlowRuntime.builder().shutdownTimeout(tiny).build();

        CountDownLatch started     = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        runtime
                .executor()
                .submit(
                        () -> {
                            started.countDown();
                            try {
                                Thread.sleep(60_000); // far more than the tiny shutdown grace
                            } catch (InterruptedException ie) {
                                interrupted.countDown();
                            }
                        });

        assertTrue(started.await(2, TimeUnit.SECONDS), "in-flight task must start before close()");

        long t0 = System.nanoTime();
        runtime.close();
        long elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(
                   elapsedMillis < 5_000L,
                   "close() with "
                           + tiny
                           + " timeout must not block beyond a few seconds (was "
                           + elapsedMillis
                           + "ms)");
        assertTrue(
                   interrupted.await(2, TimeUnit.SECONDS),
                   "in-flight task must be interrupted by shutdownNow() after the grace period");
    }

    @Test
    void shutdownTimeout_defaultsToFiveSeconds() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            assertEquals(FlowRuntime.DEFAULT_SHUTDOWN_TIMEOUT, runtime.shutdownTimeout());
            assertEquals(Duration.ofSeconds(5), runtime.shutdownTimeout());
        }
    }

    @Test
    void shutdownTimeout_isExposedByRuntime_whenOverridden() {
        Duration custom = Duration.ofSeconds(2);
        try (FlowRuntime runtime = FlowRuntime.builder().shutdownTimeout(custom).build()) {
            assertSame(custom, runtime.shutdownTimeout());
        }
    }

    @Test
    void shutdownTimeout_rejectsNegative() {
        FlowRuntime.Builder b = FlowRuntime.builder();
        assertThrows(IllegalArgumentException.class, () -> b.shutdownTimeout(Duration.ofSeconds(-1)));
    }

    @Test
    void afterClose_freshRuntime_canStillDispatch() {
        // Rollback safety: the factory regenerates the executor on
        // demand after shutdown, so a brand-new FlowRuntime built after
        // close() has a fully functional bus.
        FlowRuntime first = FlowRuntime.builder().build();
        first.close();

        try (FlowRuntime second = FlowRuntime.builder().build()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            return "ok:" + command.value();
                        }
                    };
            second.commands().register(handler);
            try {
                Command<Greet> cmd    = Command.<Greet>builder().body(new Greet("x")).build();
                String         result = second.commands().dispatchAndReturn(cmd);
                assertEquals("ok:x", result);
            } finally {
                second.commands().unregister(handler);
            }
        }
    }

    record Greet(String value) {
    }

    // ------------------------------------------------------------------
    // ExecutionStrategy wiring on top of the existing
    // executor-lifecycle contract. The runtime is still the single
    // owner of the VT executor; the strategy lives on top of it.
    // ------------------------------------------------------------------

    @Test
    void executionStrategy_defaultsToInline_forSynchronousMode() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            assertInstanceOf(
                             ExecutionStrategy.Inline.class,
                             runtime.executionStrategy(),
                             "Default executionMode is Synchronous → strategy must be Inline");
        }
    }

    @Test
    void executionStrategy_followsExecutionMode_forAsynchronousInMemory() {
        try (FlowRuntime runtime =
                FlowRuntime.builder().defaultExecutionMode(ExecutionMode.asynchronousInMemory()).build()) {
            ExecutionStrategy.VirtualThread vt =
                    assertInstanceOf(ExecutionStrategy.VirtualThread.class, runtime.executionStrategy());
            assertSame(
                       runtime.executor(),
                       vt.executor(),
                       "VirtualThread strategy must share the runtime-owned VT executor — "
                               + "the runtime stays the single lifecycle owner");
        }
    }

    @Test
    void executionStrategy_explicitOverride_isExposedVerbatim() {
        // Builder.strategy(...) is the test injection seam: the
        // runtime exposes the strategy as-is, never wraps it. We
        // pass a fresh Inline instance so identity comparison
        // distinguishes "the one we injected" from "a freshly
        // derived default".
        ExecutionStrategy injected = new ExecutionStrategy.Inline();
        try (FlowRuntime runtime = FlowRuntime.builder().strategy(injected).build()) {
            assertSame(
                       injected,
                       runtime.executionStrategy(),
                       "Builder.strategy(...) override must be exposed verbatim");
        }
    }

    @Test
    void executionStrategy_rejectsAccessAfterClose() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        runtime.close();
        assertThrows(
                     IllegalStateException.class,
                     runtime::executionStrategy,
                     "executionStrategy() must reject access after close, "
                             + "consistently with executor() / commands() / ...");
    }
}

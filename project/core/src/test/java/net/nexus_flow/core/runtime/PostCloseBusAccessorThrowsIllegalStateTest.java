package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.nexus_flow.core.runtime.exceptions.FlowRuntimeClosedException;
import org.junit.jupiter.api.Test;

/**
 * Audit §6 T1. Pins that {@code runtime.commands() / queries() / events() / executionStrategy() /
 * executor() / concurrencyGate()} all throw {@link FlowRuntimeClosedException} after the runtime
 * has been closed.
 *
 * <p>The production code at {@code DefaultFlowRuntime.ensureOpen()} guards every accessor that
 * could expose live resources; without this regression a future refactor that drops the {@code
 * ensureOpen()} call on any one of them could silently leak a closed bus, an interrupted executor,
 * or a deflated concurrency gate to application code.
 *
 * <p>Accessors that do NOT throw post-close (by design) and are exercised here as a separate
 * contract: {@code errorPolicy()}, {@code executionMode()}, {@code shutdownTimeout()}, {@code
 * interceptors()}. These return immutable value-typed configuration that holds no live resources;
 * dispatch entry points short-circuit on the closed flag before consulting them.
 */
class PostCloseBusAccessorThrowsIllegalStateTest {

    @Test
    void afterClose_allLiveResourceAccessorsThrow() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        runtime.close();

        assertThrows(FlowRuntimeClosedException.class, runtime::commands, "commands()");
        assertThrows(FlowRuntimeClosedException.class, runtime::queries, "queries()");
        assertThrows(FlowRuntimeClosedException.class, runtime::events, "events()");
        assertThrows(
                     FlowRuntimeClosedException.class, runtime::executionStrategy, "executionStrategy()");
        assertThrows(FlowRuntimeClosedException.class, runtime::executor, "executor()");
        assertThrows(FlowRuntimeClosedException.class, runtime::concurrencyGate, "concurrencyGate()");
    }

    @Test
    void afterClose_immutableConfigAccessors_doNotThrow() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        runtime.close();

        // These accessors return value-typed configuration. They MUST NOT throw post-close because
        // observability tools (e.g. a shutdown-hook handler that wants to log the configured timeout)
        // need to be able to inspect the runtime even after it has been closed.
        assertDoesNotThrow(runtime::errorPolicy, "errorPolicy()");
        assertDoesNotThrow(runtime::executionMode, "executionMode()");
        assertDoesNotThrow(runtime::shutdownTimeout, "shutdownTimeout()");
        assertDoesNotThrow(runtime::interceptors, "interceptors()");
    }

    @Test
    void close_isIdempotent_andSubsequentAccessorsStillThrow() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        runtime.close();
        runtime.close(); // idempotent — must not throw
        assertThrows(FlowRuntimeClosedException.class, runtime::commands);
    }
}

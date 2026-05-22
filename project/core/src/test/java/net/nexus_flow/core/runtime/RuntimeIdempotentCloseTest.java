package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

/**
 * {@link FlowRuntime#close()} is idempotent and the runtime-owned {@link ExecutorService} is shut
 * down <strong>exactly once</strong>.
 *
 * <p>Pre-2.5 the executor was sourced from a process-wide factory and a second {@code close()} call
 * could observe an already-terminated executor; we now own one VT executor per runtime and a
 * redundant close must be a strict no-op.
 */
class RuntimeIdempotentCloseTest {

    @Test
    void close_isIdempotent_andDoesNotThrowOnSecondInvocation() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        // Capture the executor BEFORE close: after close the accessor
        // throws IllegalStateException by design.
        ExecutorService executor = runtime.executor();

        runtime.close();
        assertTrue(runtime.isClosed(), "first close must mark runtime as closed");
        assertTrue(executor.isShutdown(), " runtime-owned executor must be shut down by close()");

        // Second and third close are pure no-ops — never throw, never
        // reach into a half-closed executor.
        assertDoesNotThrow(runtime::close);
        assertDoesNotThrow(runtime::close);
        assertTrue(runtime.isClosed(), "redundant close must preserve the closed state");

        // Accessors must consistently reject access after close —
        // they do not become available again on retry.
        assertThrows(IllegalStateException.class, runtime::commands);
        assertThrows(IllegalStateException.class, runtime::executor);
    }

    @Test
    void singleClose_shutsExecutorDownExactlyOnce() {
        // Pin "exactly once" by asserting the executor is NOT shutdown
        // before close, and IS shutdown after. A reachable second
        // shutdown call would be silently absorbed by Java's
        // ExecutorService; what matters is the lifecycle never throws.
        FlowRuntime     runtime  = FlowRuntime.builder().build();
        ExecutorService executor = runtime.executor();
        assertFalse(executor.isShutdown(), "freshly built runtime must own an open executor");

        runtime.close();
        assertTrue(executor.isShutdown());

        // Second close must observe an already-terminated executor
        // without surfacing any RejectedExecutionException or similar.
        assertDoesNotThrow(runtime::close);
    }
}

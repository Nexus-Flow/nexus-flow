package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Lifecycle contract for {@link FlowRuntime}.
 *
 * <p>Covers build → live → close → idempotent close, plus try-with-resources behaviour. Bus wiring
 * is out of scope for this slice.
 */
class FlowRuntimeLifecycleTest {

    @Test
    void builder_returnsNewInstance_onEachInvocation() {
        // Each builder is its own object — we don't want a shared singleton
        // that would makereproduce the singleton problem we're
        // trying to remove.
        FlowRuntime.Builder b1 = FlowRuntime.builder();
        FlowRuntime.Builder b2 = FlowRuntime.builder();
        assertNotNull(b1);
        assertNotNull(b2);
        assertNotSame(b1, b2);
    }

    @Test
    void build_producesOpenRuntime() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            assertFalse(runtime.isClosed(), "freshly built runtime must be open");
        }
    }

    @Test
    void close_marksRuntimeAsClosed() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        runtime.close();
        assertTrue(runtime.isClosed());
    }

    @Test
    void close_isIdempotent() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        runtime.close();
        // Second close must not throw and must keep the closed state.
        runtime.close();
        runtime.close();
        assertTrue(runtime.isClosed());
    }

    @Test
    void tryWithResources_closesRuntimeOnExit() {
        FlowRuntime captured;
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            captured = runtime;
            assertFalse(runtime.isClosed());
        }
        assertTrue(captured.isClosed(), "try-with-resources must drive close() on normal exit");
    }

    @Test
    void tryWithResources_closesRuntime_onExceptionalExit() {
        FlowRuntime captured = null;
        try {
            try (FlowRuntime runtime = FlowRuntime.builder().build()) {
                captured = runtime;
                throw new IllegalStateException("boom");
            }
        } catch (IllegalStateException expected) {
            // swallow — the contract under test is that close() runs anyway
        }
        assertNotNull(captured);
        assertTrue(captured.isClosed(), "try-with-resources must drive close() on exceptional exit");
    }

    @SuppressWarnings(
        "try") // Intentional: closes one resource early to verify independent runtime lifecycles.
    @Test
    void independentRuntimes_haveIndependentLifecycles() {
        // Closing one runtime must not affect another — explicit guarantee
        // against any accidental static state.
        try (FlowRuntime a = FlowRuntime.builder().build();
                FlowRuntime b = FlowRuntime.builder().build()) {
            a.close();
            assertTrue(a.isClosed());
            assertFalse(b.isClosed(), "closing one runtime must not close another");
        }
    }
}

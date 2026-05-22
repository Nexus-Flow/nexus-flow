package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * exercises {@link FlowRuntime#shutdown(Duration)}.
 *
 * <p>Scenarios:
 *
 * <ul>
 * <li>shutdown(Duration.ZERO) — immediate shutdownNow path; runtime is closed and {@link
 * FlowRuntime#isClosed()} returns true.
 * <li>Second call is a no-op.
 * <li>Negative timeout is rejected.
 * <li>{@code close()} delegates to shutdown(shutdownTimeout).
 * </ul>
 */
class FlowRuntimeShutdownTimeoutTest {

    @Test
    void shutdown_withZeroDuration_closesImmediately() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        assertFalse(runtime.isClosed());
        runtime.shutdown(Duration.ZERO);
        assertTrue(runtime.isClosed());
        // Second invocation: no-op.
        runtime.shutdown(Duration.ofSeconds(1));
        assertTrue(runtime.isClosed());
    }

    @Test
    void shutdown_rejectsNegativeTimeout() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            assertThrows(IllegalArgumentException.class, () -> runtime.shutdown(Duration.ofSeconds(-1)));
            assertFalse(runtime.isClosed(), "rejected shutdown must NOT flip the closed flag");
        }
    }

    @Test
    void shutdown_rejectsNullTimeout() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            assertThrows(NullPointerException.class, () -> runtime.shutdown(null));
        }
    }

    @Test
    void close_delegatesToShutdownWithBuilderTimeout() {
        FlowRuntime runtime = FlowRuntime.builder().shutdownTimeout(Duration.ofMillis(50)).build();
        runtime.close();
        assertTrue(runtime.isClosed());
    }
}

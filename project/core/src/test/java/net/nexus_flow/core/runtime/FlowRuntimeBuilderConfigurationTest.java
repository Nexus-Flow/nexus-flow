package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * {@link FlowRuntime.Builder} configuration of default {@link ErrorPolicy} and {@link
 * ExecutionMode}.
 *
 * <p>keeps the wiring inert: builder accepts overrides, runtime exposes them, but bus accessors do
 * <em>not</em> consume them yet.
 */
class FlowRuntimeBuilderConfigurationTest {

    @Test
    void defaults_areFailFastAndSynchronous() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            assertSame(
                       ErrorPolicy.failFast(),
                       runtime.errorPolicy(),
                       "default ErrorPolicy is FailFast ( default for nested commands)");
            assertSame(
                       ExecutionMode.synchronous(),
                       runtime.executionMode(),
                       "default ExecutionMode is Synchronous");
        }
    }

    @Test
    void builder_overrideErrorPolicy_isExposedByRuntime() {
        ErrorPolicy custom = ErrorPolicy.collectFailures();
        try (FlowRuntime runtime = FlowRuntime.builder().defaultErrorPolicy(custom).build()) {
            assertSame(custom, runtime.errorPolicy());
            assertSame(
                       ExecutionMode.synchronous(),
                       runtime.executionMode(),
                       "non-overridden defaults remain at FailFast / Synchronous");
        }
    }

    @Test
    void builder_overrideExecutionMode_isExposedByRuntime() {
        ExecutionMode custom = ExecutionMode.asynchronousInMemory();
        try (FlowRuntime runtime = FlowRuntime.builder().defaultExecutionMode(custom).build()) {
            assertSame(custom, runtime.executionMode());
            assertSame(ErrorPolicy.failFast(), runtime.errorPolicy());
        }
    }

    @Test
    void builder_overridesBoth_withFluentChaining() {
        ErrorPolicy   ep = ErrorPolicy.isolate(ErrorPolicy.collectFailures());
        ExecutionMode em = ExecutionMode.asynchronousInMemory();

        try (FlowRuntime runtime =
                FlowRuntime.builder().defaultErrorPolicy(ep).defaultExecutionMode(em).build()) {
            assertSame(ep, runtime.errorPolicy());
            assertSame(em, runtime.executionMode());
        }
    }

    @Test
    void builder_rejectsNullPolicyOrMode() {
        FlowRuntime.Builder b = FlowRuntime.builder();
        assertThrows(NullPointerException.class, () -> b.defaultErrorPolicy(null));
        assertThrows(NullPointerException.class, () -> b.defaultExecutionMode(null));
    }

    @Test
    void closedRuntime_stillExposesConfiguration_butRejectsBusAccess() {
        // Configuration values are pure data; reading them on a closed
        // runtime is harmless and useful for diagnostics. Bus access
        // remains forbidden post-close.
        FlowRuntime runtime =
                FlowRuntime.builder()
                        .defaultErrorPolicy(ErrorPolicy.collectFailures())
                        .defaultExecutionMode(ExecutionMode.asynchronousInMemory())
                        .build();
        runtime.close();

        assertTrue(runtime.isClosed());
        assertSame(ErrorPolicy.collectFailures(), runtime.errorPolicy());
        assertSame(ExecutionMode.asynchronousInMemory(), runtime.executionMode());

        assertThrows(IllegalStateException.class, runtime::commands);
        assertThrows(IllegalStateException.class, runtime::queries);
        assertThrows(IllegalStateException.class, runtime::events);
    }

    @Test
    void builder_lastWriterWins_perKnob() {
        try (FlowRuntime runtime =
                FlowRuntime.builder()
                        .defaultErrorPolicy(ErrorPolicy.collectFailures())
                        .defaultErrorPolicy(ErrorPolicy.failFast())
                        .defaultExecutionMode(ExecutionMode.asynchronousInMemory())
                        .defaultExecutionMode(ExecutionMode.synchronous())
                        .build()) {
            assertEquals(ErrorPolicy.failFast(), runtime.errorPolicy());
            assertEquals(ExecutionMode.synchronous(), runtime.executionMode());
        }
    }
}

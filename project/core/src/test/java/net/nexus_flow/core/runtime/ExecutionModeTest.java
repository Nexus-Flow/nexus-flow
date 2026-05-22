package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * type-level contract for {@link ExecutionMode}.
 *
 * <p>Synchronous and AsynchronousInMemory are singletons; AsynchronousDurable is declared on the
 * sealed hierarchy but cannot be constructed in .
 */
class ExecutionModeTest {

    @Test
    void synchronous_isASingleton() {
        assertSame(ExecutionMode.synchronous(), ExecutionMode.synchronous());
        assertNotNull(ExecutionMode.synchronous());
        assertInstanceOf(ExecutionMode.Synchronous.class, ExecutionMode.synchronous());
    }

    @Test
    void asynchronousInMemory_isASingleton() {
        assertSame(ExecutionMode.asynchronousInMemory(), ExecutionMode.asynchronousInMemory());
        assertNotNull(ExecutionMode.asynchronousInMemory());
        assertInstanceOf(
                         ExecutionMode.AsynchronousInMemory.class, ExecutionMode.asynchronousInMemory());
    }

    @Test
    void asynchronousDurable_isConstructible_viaFactorySince_4() {
        // the constructor is now a no-op; the
        // durable boundary is wired through DurableDispatch instead of
        // throwing at the runtime layer.
        ExecutionMode.AsynchronousDurable durable =
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(ExecutionMode::asynchronousDurable);
        assertNotNull(durable);
        assertInstanceOf(ExecutionMode.AsynchronousDurable.class, durable);
    }

    @Test
    void asynchronousDurable_isConstructible_viaDirectConstructorSince_4() {
        // Mirrors the factory test: direct constructor invocation is
        // also a no-op since.
        ExecutionMode.AsynchronousDurable durable =
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(ExecutionMode.AsynchronousDurable::new);
        assertNotNull(durable);
    }

    @Test
    void switchExpression_overExecutionMode_isExhaustive() {
        ExecutionMode[] samples = {ExecutionMode.synchronous(), ExecutionMode.asynchronousInMemory()};
        String[]        tags    = new String[samples.length];
        for (int i = 0; i < samples.length; i++) {
            tags[i] =
                    switch (samples[i]) {
                        case ExecutionMode.Synchronous _  -> "sync";
                        case ExecutionMode.AsynchronousInMemory _ -> "async-mem";
                        case ExecutionMode.AsynchronousDurable _ -> "async-durable";
                    };
        }
        assertEquals("sync", tags[0]);
        assertEquals("async-mem", tags[1]);
    }
}

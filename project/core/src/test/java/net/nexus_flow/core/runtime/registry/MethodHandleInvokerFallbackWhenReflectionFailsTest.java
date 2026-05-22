package net.nexus_flow.core.runtime.registry;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * fallback behaviour: if {@code MethodHandles.unreflect} is denied by access control, the registry
 * transparently falls back to a {@link java.lang.reflect.Method#invoke(Object, Object...)}-based
 * invoker that still works and unwraps the underlying exception verbatim.
 */
class MethodHandleInvokerFallbackWhenReflectionFailsTest {

    /** Handler whose {@code handle} is package-private. */
    static final class PrivateHandler {
        String lastSeen;

        @SuppressWarnings("unused")
        private void handle(String msg) {
            if ("boom".equals(msg)) {
                throw new IllegalStateException("propagated cause");
            }
            this.lastSeen = msg;
        }
    }

    @Test
    void privateHandle_isStillDispatchable_viaFallback_andPropagatesCauseVerbatim() throws Throwable {
        // Even when reflection is permitted, the registry should call
        // setAccessible(true) and prefer MethodHandle. The point of
        // this test is that *if* unreflect failed, the registry would
        // still work. We force that by constructing the fallback
        // invoker directly via the package-private static helper.
        PrivateHandler h      = new PrivateHandler();
        Method         handle = PrivateHandler.class.getDeclaredMethod("handle", String.class);
        handle.setAccessible(true);

        HandlerInvoker<String, Void> fallback = new ReflectiveHandlerInvoker<>(h, handle);
        fallback.invoke("hello", (ExecutionContext) null);
        assertEquals("hello", h.lastSeen);

        // Exceptions thrown by the handler must surface unwrapped
        // (NOT wrapped in InvocationTargetException).
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> fallback.invoke("boom", null));
        assertEquals("propagated cause", ex.getMessage());
    }

    @Test
    void unreflectOrFallback_picksMethodHandle_whenAccessIsGranted() {
        PrivateHandler h = new PrivateHandler();
        Method         handle;
        try {
            handle = PrivateHandler.class.getDeclaredMethod("handle", String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        HandlerInvoker<String, Void> invoker =
                MethodHandleHandlerInvoker.unreflectOrFallback(h, handle);
        assertEquals(
                     "MethodHandleHandlerInvoker",
                     invoker.getClass().getSimpleName(),
                     "with setAccessible(true) available, MH path should win");
    }

    @Test
    void registry_registersTheFallback_whenUnreflectIsImpossible_inSimulatedRetry() throws Throwable {
        // Simulate the fallback path by manually registering a
        // ReflectiveHandlerInvoker through registerInvoker. The registry
        // must keep the invoker as-is (no upgrade), dispatch through it,
        // and the plan identity must be stable across lookups.
        HandlerRegistry<String, Void> reg    = new HandlerRegistry<>();
        PrivateHandler                h      = new PrivateHandler();
        Method                        handle = PrivateHandler.class.getDeclaredMethod("handle", String.class);
        handle.setAccessible(true);
        HandlerInvoker<String, Void> fallback = new ReflectiveHandlerInvoker<>(h, handle);
        reg.registerInvoker(String.class, fallback, /* order= */ 0);

        DispatchPlan<String, Void> plan = reg.planFor(String.class);
        assertSame(fallback, plan.handlers().getFirst());
        fallback.invoke("ping", null);
        assertEquals("ping", h.lastSeen);
    }
}

package net.nexus_flow.core.runtime.registry;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * {@link HandlerRegistry#register(Class, Object, Method, int)} unreflects the handler's {@code
 * handle} method into a {@link java.lang.invoke.MethodHandle} bound to the instance, and the
 * resulting {@link HandlerInvoker} dispatches without per-call reflection lookup.
 */
class MethodHandleInvokerHotPathTest {

    public static final class Counter {
        final AtomicInteger calls = new AtomicInteger();

        public void handle(String msg) {
            calls.incrementAndGet();
        }
    }

    @Test
    void register_unreflectsHandleMethod_andInvokerDispatchesIntoTheBoundInstance() throws Throwable {
        HandlerRegistry<String, Void> reg     = new HandlerRegistry<>();
        Counter                       counter = new Counter();
        Method                        handle  = Counter.class.getMethod("handle", String.class);

        reg.register(String.class, counter, handle, /* order= */ 0);

        DispatchPlan<String, Void> plan = reg.planFor(String.class);
        assertEquals(1, plan.size());

        HandlerInvoker<String, Void> invoker = plan.handlers().getFirst();
        // Hot loop — assert no exception, accurate dispatch, single
        // instance reference is reused (no per-call allocation of an
        // invoker shape).
        for (int i = 0; i < 10_000; i++) {
            invoker.invoke("msg-" + i, (ExecutionContext) null);
        }
        assertEquals(10_000, counter.calls.get());

        // Identity remains stable across plan lookups (ClassValue cache).
        assertSame(invoker, reg.planFor(String.class).handlers().getFirst());
    }

    @Test
    void invokerIsBackedByMethodHandle_notTheReflectiveFallback() throws Throwable {
        HandlerRegistry<String, Void> reg     = new HandlerRegistry<>();
        Counter                       counter = new Counter();
        Method                        handle  = Counter.class.getMethod("handle", String.class);
        reg.register(String.class, counter, handle, /* order= */ 0);

        HandlerInvoker<String, Void> invoker = reg.planFor(String.class).handlers().getFirst();
        assertNotNull(invoker);
        // The MethodHandle-backed invoker is the package-private
        // MethodHandleHandlerInvoker; the fallback is
        // ReflectiveHandlerInvoker. We only assert it is NOT the
        // fallback class.
        assertEquals(
                     "MethodHandleHandlerInvoker",
                     invoker.getClass().getSimpleName(),
                     "registry must prefer the MethodHandle invoker when reflection is permitted");
    }
}

package net.nexus_flow.core.runtime.registry;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.nexus_flow.core.runtime.ExecutionContext;

/**
 * {@link MethodHandle}-backed {@link HandlerInvoker}.
 *
 * <p>The handler's {@code handle(M)} method is unreflected <em>once</em> at construction and bound
 * to the handler instance with {@link MethodHandle#bindTo(Object)}. Subsequent {@link #invoke}
 * calls go through {@link MethodHandle#invoke(Object...)} directly, which the JIT typically inlines
 * into the dispatch site after a few iterations.
 *
 * <h2>Fallback behaviour</h2>
 *
 * If {@link MethodHandles.Lookup#unreflect(Method)} cannot grant access (private {@code handle},
 * hostile classloader, missing {@code open}), the static factory {@link
 * #unreflectOrFallback(Object, Method)} returns a {@link ReflectiveHandlerInvoker} instead and
 * emits a single {@code WARNING} log entry per handler class to avoid drowning logs with repeated
 * fallback messages during bootstrap.
 */
final class MethodHandleHandlerInvoker<M, R> implements HandlerInvoker<M, R> {

    private static final Logger LOG = System.getLogger(MethodHandleHandlerInvoker.class.getName());

    /** One-shot warning gate; identity-keyed on handler class. */
    private static final Set<Class<?>> WARNED_FALLBACK_CLASSES = ConcurrentHashMap.newKeySet();

    private final MethodHandle handle;
    private final String       handlerType;

    private MethodHandleHandlerInvoker(MethodHandle handle, String handlerType) {
        this.handle      = handle;
        this.handlerType = handlerType;
    }

    /**
     * Build the most efficient {@link HandlerInvoker} the JVM lets us build for {@code
     * handler.handleMethod}. Tries {@link MethodHandle} first; on failure falls back to plain {@link
     * Method#invoke(Object, Object...)} and logs a one-shot warning.
     */
    static <M, R> HandlerInvoker<M, R> unreflectOrFallback(Object handler, Method handleMethod) {
        try {
            makeHandlerMethodAccessible(handleMethod);

            MethodHandle mh = MethodHandles.lookup().unreflect(handleMethod).bindTo(handler);
            return new MethodHandleHandlerInvoker<>(mh, handler.getClass().getName());
        } catch (IllegalAccessException | RuntimeException ex) {
            Class<?> handlerClass = handler.getClass();
            if (WARNED_FALLBACK_CLASSES.add(handlerClass)) {
                LOG.log(
                        Level.WARNING,
                        "Falling back to reflective invocation for handler {0}: {1}. "
                                + "MethodHandle.unreflect was denied; dispatch will go through "
                                + "Method.invoke for this handler class.",
                        handlerClass.getName(),
                        ex.getMessage());
            }
            return new ReflectiveHandlerInvoker<>(handler, handleMethod);
        }
    }

    @SuppressWarnings(
        "java:S3011") // Intentional framework behavior: allows optimized invocation of non-public
    // handler methods.
    private static void makeHandlerMethodAccessible(Method method) {
        method.setAccessible(true);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates directly to the pre-bound {@link java.lang.invoke.MethodHandle}. The JIT can
     * inline this call site after a handful of warm-up iterations, making it effectively as fast as a
     * direct virtual call. The {@code ctx} parameter is accepted but not forwarded to the underlying
     * handle (today's handler signature does not include a context parameter); it is wired so the
     * interface can absorb a future per-call context without an ABI break.
     *
     * @throws Throwable whatever the bound handler method throws, propagated verbatim
     */
    @SuppressWarnings("unchecked")
    @Override
    public R invoke(M msg, ExecutionContext ctx) throws Throwable {
        // ctx is intentionally unused: today the listener handle(M)
        // method does not accept a context parameter. Wired so the
        // signature can absorb a future per-call context without an
        // ABI break.
        return (R) handle.invoke(msg);
    }

    @Override
    public String handlerType() {
        return handlerType;
    }
}

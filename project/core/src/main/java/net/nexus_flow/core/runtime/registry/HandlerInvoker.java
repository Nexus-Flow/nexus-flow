package net.nexus_flow.core.runtime.registry;

import net.nexus_flow.core.runtime.ExecutionContext;

/**
 * Hot-path invocation primitive. One {@link HandlerInvoker} is bound to one concrete handler
 * instance at registration time; on dispatch the registry just calls {@link #invoke(Object,
 * ExecutionContext)} directly, with no per-call reflection lookup.
 *
 * <p>The default implementation is {@link MethodHandleHandlerInvoker}, which unreflects the
 * handler's {@code handle(...)} method once and stores a {@code MethodHandle} bound to the handler
 * instance. If reflection access cannot be granted (private method, hostile classloader, etc.) the
 * registry falls back to {@link ReflectiveHandlerInvoker} and emits a single {@code
 * java.lang.System.Logger} warning for that handler class.
 *
 * @param <M> message / event type the handler consumes
 * @param <R> handler result type ({@code Void} for void-returning listeners)
 */
@FunctionalInterface
public interface HandlerInvoker<M, R> {

    /**
     * Invoke the bound handler with {@code msg} under the supplied {@code ctx}. The {@code ctx}
     * parameter is currently unused by event listeners (they do not see an {@link ExecutionContext}
     * directly today) and is wired so the API can evolve without a signature change.
     *
     * @throws Throwable whatever the underlying handler throws, unwrapped (the registry does not wrap
     *                   into {@code InvocationTargetException}; guarantees verbatim cause propagation).
     */
    R invoke(M msg, ExecutionContext ctx) throws Throwable;

    /**
     * name of the bound handler's class, for observability (JFR custom events, future tracing).
     * Default returns the empty string so existing {@link HandlerInvoker} implementations (test
     * doubles, custom invokers) keep working unchanged. The built-in {@link
     * MethodHandleHandlerInvoker} and {@link ReflectiveHandlerInvoker} both override this to return
     * the bound handler's fully-qualified class name.
     */
    default String handlerType() {
        return "";
    }
}

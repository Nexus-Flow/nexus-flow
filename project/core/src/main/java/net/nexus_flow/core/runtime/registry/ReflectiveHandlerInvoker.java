package net.nexus_flow.core.runtime.registry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ThrowableUtils;

/**
 * Slow-path fallback when {@link java.lang.invoke.MethodHandles.Lookup#unreflect} is denied. Uses
 * plain {@link Method#invoke(Object, Object...)} and unwraps {@link InvocationTargetException} so
 * callers see the underlying exception verbatim, matching the {@link MethodHandleHandlerInvoker}
 * contract.
 */
final class ReflectiveHandlerInvoker<M, R> implements HandlerInvoker<M, R> {

    private final Object handler;
    private final Method handleMethod;

    /**
     * Creates a reflective invoker bound to {@code handler}'s {@code handleMethod}.
     *
     * @param handler      the handler instance to invoke; never {@code null}
     * @param handleMethod the {@code handle(M)} method; must be accessible (or have been made
     *                     accessible via {@link java.lang.reflect.Method#setAccessible(boolean)})
     */
    ReflectiveHandlerInvoker(Object handler, Method handleMethod) {
        this.handler      = handler;
        this.handleMethod = handleMethod;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link java.lang.reflect.Method#invoke(Object, Object...)} and unwraps any
     * {@link java.lang.reflect.InvocationTargetException} so callers see the original cause, matching
     * the contract of {@link MethodHandleHandlerInvoker}.
     *
     * @throws Throwable the original exception thrown by the handler, unwrapped from {@code
     *     InvocationTargetException} if necessary
     */
    @SuppressWarnings("unchecked")
    @Override
    public R invoke(M msg, ExecutionContext ctx) throws Throwable {
        try {
            return (R) handleMethod.invoke(handler, msg);
        } catch (InvocationTargetException ite) {
            // Unwrap so the caller sees the original cause and never
            // an ITE wrapper, mirroring MethodHandle.invoke semantics.
            Throwable cause = ite.getCause();
            throw ThrowableUtils.withSuppressed(cause != null ? cause : ite, ite);
        }
    }

    @Override
    public String handlerType() {
        return handler.getClass().getName();
    }
}

package net.nexus_flow.core.runtime.reflect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Centralised translation of {@link InvocationTargetException} into the rethrowable form expected
 * by the framework's method-handle-based invokers.
 *
 * <p>Three method adapters — for command handlers, event listeners, and query handlers — used to
 * inline the same three-branch unwrap pattern (RuntimeException → rethrow; Error → rethrow;
 * checked-or-null → wrap in IllegalStateException with a role-flavoured message). They differed
 * only in the role label baked into the wrapped message ("Handler", "Listener", "Query handler").
 * Keeping the unwrap logic in one place ensures:
 *
 * <ul>
 * <li>Future changes (e.g. preserve a richer cause chain, log a one-time WARNING on Error
 * propagation, surface a structured FlowError.Technical) land in one place rather than
 * drifting across three.
 * <li>Future adapters (a saga step adapter, a projection step adapter) get the correct unwrap
 * semantics for free.
 * </ul>
 *
 * <p>This class is framework-internal; adapter modules implement their own SPI and never call this
 * directly.
 */
public final class ReflectiveInvocationPropagator {

    private ReflectiveInvocationPropagator() {
    }

    /**
     * Translate an {@link InvocationTargetException} thrown by a reflective {@link
     * Method#invoke(Object, Object...)} call into a {@link RuntimeException} the caller can {@code
     * throw}, OR rethrow a wrapping {@link Error} directly.
     *
     * <p>Semantics (pin: see {@code ReflectiveInvocationPropagatorTest}):
     *
     * <ul>
     * <li>If {@code cause} is a {@link RuntimeException} — return it verbatim. The caller writes
     * {@code throw propagate(...)}.
     * <li>If {@code cause} is an {@link Error} — throw it directly (errors are JVM-level and must
     * not be wrapped).
     * <li>If {@code cause} is a checked exception or {@code null} — wrap in an {@link
     * IllegalStateException} with a message that includes the {@code roleLabel} (e.g. "handler
     * method", "listener method", "query handler method") and the {@code method} signature. The
     * wrapped cause is the {@code cause} if non-null, else the original {@link
     * InvocationTargetException}.
     * </ul>
     *
     * @param roleLabel short noun phrase describing the invocation role; appears in the error message
     *                  of the wrapped {@link IllegalStateException}; must not be {@code null} or blank
     * @param method    the reflectively invoked method; included in the error message; must not be
     *                  {@code null}
     * @param ite       the original {@link InvocationTargetException} captured by the caller; must not be
     *                  {@code null}
     * @return a {@link RuntimeException} the caller throws (when the cause was a {@link
     *         RuntimeException} or wrapped as {@link IllegalStateException})
     * @throws Error if {@code cause} is an {@link Error} — propagated directly to honour JVM error
     *               semantics
     */
    public static RuntimeException propagate(
            String roleLabel, Method method, InvocationTargetException ite) {
        Throwable cause = ite.getCause();
        return switch (cause) {
            case RuntimeException re -> re;
            case Error error         -> throw error;
            case null                -> new IllegalStateException(roleLabel + " failed: " + method, ite);
            default                  -> new IllegalStateException(roleLabel + " failed: " + method, cause);
        };
    }
}

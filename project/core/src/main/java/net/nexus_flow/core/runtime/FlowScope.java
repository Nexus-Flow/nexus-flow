package net.nexus_flow.core.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Implicit propagation of the current {@link ExecutionContext} via a {@link ScopedValue}.
 *
 * <p>The runtime wraps every handler invocation in {@link #runWithContext(ExecutionContext,
 * Runnable)} (or the {@link #getWithContext(ExecutionContext, Supplier)} variant) so that code
 * anywhere inside a handler can look up the active context with {@link #current()} without it
 * having to be threaded through the call chain. Handler authors should still prefer accepting the
 * context explicitly; {@code FlowScope} exists for cross-cutting concerns (logging, tracing,
 * metrics) where threading would be impractical.
 *
 * <p>Why {@link ScopedValue} instead of a {@link ThreadLocal}:
 *
 * <ul>
 * <li>It is immutable for the duration of the bound region — child virtual threads inherit it but
 * cannot rebind it for the parent.
 * <li>It interacts correctly with {@code StructuredTaskScope} forks, which is the execution model
 * <li>It avoids the cross-flow leakage that bit the JVM-wide {@link
 * net.nexus_flow.core.cqrs.event.DomainEventContext} sink.
 * </ul>
 */
public final class FlowScope {

    /**
     * The bound execution context, scoped to the dynamic extent of a {@code runWithContext} / {@code
     * getWithContext} call.
     */
    public static final ScopedValue<ExecutionContext> CURRENT_CONTEXT = ScopedValue.newInstance();

    private FlowScope() {
        // utility
    }

    /**
     * Run {@code body} with {@code context} bound as the current {@link #CURRENT_CONTEXT}. Returns
     * when {@code body} returns; the binding is unwound on exit (including exceptional exit).
     */
    public static void runWithContext(ExecutionContext context, Runnable body) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(body, "body");
        ScopedValue.where(CURRENT_CONTEXT, context).run(body);
    }

    /**
     * Variant of {@link #runWithContext(ExecutionContext, Runnable)} that returns a value computed by
     * {@code body}.
     */
    public static <T> T getWithContext(ExecutionContext context, Supplier<? extends T> body) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(body, "body");
        // ScopedValue.Carrier exposes call(CallableOp) but no get(Supplier);
        // we adapt the Supplier to a non-throwing CallableOp so the bound
        // region can produce a value without forcing callers to deal with
        // checked exceptions.
        return ScopedValue.where(CURRENT_CONTEXT, context).call(body::get);
    }

    /**
     * Returns a pre-built {@link ScopedValue.Carrier} that binds {@code context} as the {@link
     * #CURRENT_CONTEXT} when {@link ScopedValue.Carrier#run run} or {@link ScopedValue.Carrier#call
     * call} is invoked.
     *
     * <p>Callers that need to invoke the same context across many operations in a tight loop should
     * obtain one carrier once and reuse it: creating a carrier is cheaper than calling {@link
     * #runWithContext} or {@link #getWithContext} repeatedly when the context does not change between
     * iterations (those helpers each allocate a fresh carrier internally).
     *
     * <p>Typical use: build the carrier before entering a processing loop, then call {@code
     * carrier.call(() -> body(item))} for each item.
     *
     * @param context the context to bind; must not be {@code null}
     * @return a reusable {@link ScopedValue.Carrier} bound to {@code context}
     */
    public static ScopedValue.Carrier carrierFor(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        return ScopedValue.where(CURRENT_CONTEXT, context);
    }

    /**
     * The current execution context, if any. Empty when called outside a {@code runWithContext} /
     * {@code getWithContext} dynamic extent.
     */
    public static Optional<ExecutionContext> current() {
        return CURRENT_CONTEXT.isBound() ? Optional.of(CURRENT_CONTEXT.get()) : Optional.empty();
    }

    /**
     * The current execution context, throwing {@link
     * net.nexus_flow.core.runtime.exceptions.MissingExecutionContextException} (a subclass of {@link
     * IllegalStateException}) when no context is bound. Useful from infrastructure code that must not
     * silently lose context.
     */
    public static ExecutionContext requireCurrent() {
        if (!CURRENT_CONTEXT.isBound()) {
            throw new net.nexus_flow.core.runtime.exceptions.MissingExecutionContextException();
        }
        return CURRENT_CONTEXT.get();
    }
}

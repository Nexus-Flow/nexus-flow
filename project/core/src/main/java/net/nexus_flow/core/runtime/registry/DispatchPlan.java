package net.nexus_flow.core.runtime.registry;

import java.util.List;

/**
 * Immutable execution plan returned by {@link HandlerRegistry#planFor(Class)}.
 *
 * <p>One plan per concrete message class. Built lazily from the current {@link
 * RegistrationSnapshot} and memoised in a {@code ClassValue} so the hot path is a single lock-free
 * read.
 *
 * <p>{@link #handlers()} is the ordered, immutable invocation list already sorted by:
 *
 * <ol>
 * <li><b>Primary</b> — ascending {@code order()} priority (smaller values invoked first;
 * preserves the legacy {@code DomainEventListener#order()} semantics).
 * <li><b>Secondary</b> — registration order (FIFO) for stable tie breaking, pinned by {@code
 *       DispatchPlanMultiHandlerOrderingIsDeterministicTest}.
 * </ol>
 *
 * <p>Note: parallel listener invocation is opt-in. The plan exposes a sequential list by default;
 * {@link #allParallelSafe()} lets a bus <strong>opt</strong> into a parallel fan-out when every
 * listener in the plan has overridden {@code EventListener#parallelSafe()} to {@code true}. The
 * default remains sequential.
 */
public record DispatchPlan<M, R>(List<HandlerInvoker<M, R>> handlers, boolean allParallelSafe) {

    public DispatchPlan {
        handlers = List.copyOf(handlers);
    }

    /** Convenience: a plan with no registered handlers. */
    public static <M, R> DispatchPlan<M, R> empty() {
        return new DispatchPlan<>(List.of(), false);
    }

    /**
     * {@code true} when no handlers are registered for this plan's message type.
     *
     * @return {@code true} iff {@link #handlers()} is empty
     */
    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    /**
     * Number of handlers in this plan.
     *
     * @return the handler count; 0 when the plan is empty
     */
    public int size() {
        return handlers.size();
    }
}

package net.nexus_flow.core.runtime.dispatch;

import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowError;

/**
 * Onion-chain hook handed to each {@link DispatchInterceptor} so it can continue (or short-circuit)
 * the dispatch.
 *
 * <p>Lifetime: one {@code DispatchChain} instance is constructed per dispatch per interceptor link.
 * It is intentionally <em>not</em> a {@link java.util.function.Function} — it is a named contract
 * so future lifecycle methods (e.g. {@code beforeCompletion(...)} for outbox integration) can be
 * added without breaking existing implementations.
 *
 * <p>Thread-safety: not safe for concurrent invocation; each chain link is consumed exactly once by
 * exactly one thread (the thread running the outer interceptor).
 *
 * @param <R> the success payload type of the {@link DispatchResult} returned by the underlying
 *            dispatch
 */
@FunctionalInterface
public interface DispatchChain<R> {

    /**
     * Hand control to the next interceptor (or, at the bottom of the onion, invoke the wrapped
     * handler). The returned {@link DispatchResult} is propagated upwards verbatim unless the caller
     * chooses to transform it.
     *
     * <p>Contract: a calling interceptor MUST NOT convert a {@link DispatchResult.Failure} or {@link
     * DispatchResult.PartialFailure} returned by {@code proceed()} into a {@link
     * DispatchResult.Success}. The runtime enforces this around the call site (see {@link
     * SyncDispatcher#dispatchThrough(InvocationContext, java.util.List,
     * java.util.concurrent.Callable)}); attempts to silently upgrade a failure surface as {@link
     * FlowError.Technical}.
     */
    DispatchResult<R> proceed();
}

package net.nexus_flow.core.runtime.dispatch;

import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowError;

/**
 * Pluggable interceptor that wraps every dispatch made through the runtime.
 *
 * <p>Interceptors are middleware in the classic onion sense: when N interceptors are registered
 * through {@link FlowRuntime.Builder#interceptor(DispatchInterceptor)}, each dispatch flows through
 * them in registration order before reaching the underlying handler, and unwinds in reverse on the
 * way out. That is — <strong>the first interceptor registered is the outermost shell of the
 * onion</strong>: its {@code intercept(...)} body sees both the earliest "before" and the latest
 * "after" of the dispatch.
 *
 * <p><strong>Registration order example</strong>. With {@code
 * builder.interceptor(A).interceptor(B).interceptor(C)}, the runtime evaluates: {@code A_pre →
 * B_pre → C_pre → handler → C_post → B_post → A_post}.
 *
 * <p><strong>Why not a {@code Function<InvocationContext, DispatchResult<R>>}?</strong> The
 * named-type binding lets us:
 *
 * <ol>
 * <li>Attribute a thrown exception to a specific implementation class in the wrapping {@link
 * FlowError.Technical}.
 * <li>Grow the contract with default methods (e.g. {@code beforeCompletion(...)} for the durable
 * outbox) without breaking existing implementations.
 * </ol>
 *
 * <p><strong>Error semantics</strong>:
 *
 * <ul>
 * <li>If an interceptor throws a {@link FlowError.Domain}, it is propagated verbatim (no
 * wrapping).
 * <li>If an interceptor throws anything else, it is wrapped in a {@link FlowError.Technical} that
 * carries the {@link InvocationContext} of the failing link — including the {@link
 * InvocationStage} (PRE/INVOKE/POST) so logs can attribute the failure precisely.
 * <li>If the inner chain returns a {@link DispatchResult.Failure} or {@link
 * DispatchResult.PartialFailure}, the interceptor may transform it (e.g. enrich the cause)
 * but MUST NOT silently upgrade it to {@link DispatchResult.Success}. The runtime enforces
 * this; see {@link SyncDispatcher#dispatchThrough(InvocationContext, java.util.List,
 * java.util.concurrent.Callable)}.
 * </ul>
 *
 * <p><strong>Thread-safety.</strong> An interceptor instance is shared across every dispatch of its
 * owning runtime (potentially across many threads) and MUST therefore be implemented thread-safe.
 * The {@link DispatchChain} handed to it, by contrast, is per-dispatch and is never reused across
 * threads.
 *
 * <p><strong>Adapter-module extension point.</strong> Interceptors are the primary hook for
 * cross-cutting concerns outside {@code core}: an OpenTelemetry tracing interceptor (starts a span
 * in pre, closes it in post), a Micrometer timing interceptor, a Spring Security authorization
 * interceptor, or a Quarkus MicroProfile Metrics decorator all implement this interface. The
 * interface will not be sealed so adapter modules can contribute implementations without forking
 * {@code core}.
 *
 * <p><strong>Sealing.</strong> Originally specified as {@code sealed}; relaxed to a regular
 * interface so that downstream extensions (e.g. an OpenTelemetry interceptor) can be authored
 * outside the {@code core} module without re-opening the sealed family. The "named-type binding"
 * the brief asked for (vs. a raw {@code Function<>}) is fully preserved.
 */
@FunctionalInterface
public interface DispatchInterceptor {

    /**
     * Run this interceptor's logic for one dispatch.
     *
     * @param ctx   per-dispatch context — stage starts at {@link InvocationStage#PRE}. The mutable
     *              {@link InvocationContext#attributes()} bag is shared by every link of the chain for this
     *              dispatch.
     * @param chain the next link of the chain. Calling {@link DispatchChain#proceed()} either
     *              descends into the next interceptor or, at the bottom of the onion, invokes the wrapped
     *              handler.
     * @param <R>   success payload type
     * @return the (possibly transformed) dispatch result
     */
    <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain);
}

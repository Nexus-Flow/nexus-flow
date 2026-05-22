package net.nexus_flow.core.runtime.interceptors;

import net.nexus_flow.core.runtime.dispatch.DispatchChain;
import net.nexus_flow.core.runtime.dispatch.DispatchInterceptor;
import net.nexus_flow.core.runtime.dispatch.InvocationContext;
import net.nexus_flow.core.runtime.result.DispatchResult;

/**
 * Records the dispatch wall-clock duration into the {@link InvocationContext#attributes()
 * invocation attribute bag} under the key {@value #DURATION_MS_KEY}.
 *
 * <p>Measured with {@link System#nanoTime()}, exposed in milliseconds as a {@link Long}. The
 * attribute is written even when the dispatch fails, so callers can still observe how long a
 * Failure took.
 *
 * <p><strong>Fan-out invariant.</strong> The attribute is written into the {@link
 * InvocationContext} of <em>this</em> dispatch only; sibling fan-out children get their own {@code
 * InvocationContext} (with its own attribute bag), so the value never leaks across siblings.
 *
 * <p>Thread-safety: no instance state.
 */
public final class TimingDispatchInterceptor implements DispatchInterceptor {

    /** Key under which the dispatch duration is published. */
    public static final String DURATION_MS_KEY = "dispatch.durationMs";

    @Override
    public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
        long start = System.nanoTime();
        try {
            return chain.proceed();
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            ctx.attributes().put(DURATION_MS_KEY, durationMs);
        }
    }
}

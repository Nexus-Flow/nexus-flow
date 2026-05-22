package net.nexus_flow.core.runtime.dispatch;

import net.nexus_flow.core.runtime.result.FlowError;

/**
 * Lifecycle stage of an {@link InvocationContext} as it flows through the interceptor onion .
 *
 * <p>Used to attribute failures raised by an interceptor: a throw before the chain proceeds is
 * {@link #PRE}, a throw while the inner chain is running is {@link #INVOKE}, and a throw after the
 * chain returns is {@link #POST}. Wrapping logic in {@link
 * SyncDispatcher#dispatchThrough(InvocationContext, java.util.List, java.util.concurrent.Callable)}
 * uses the stamped stage when building the wrapping {@link FlowError.Technical}.
 */
public enum InvocationStage {
    /** Interceptor body, before {@code chain.proceed()} has been called. */
    PRE,
    /** Inside {@code chain.proceed()} — the inner chain (or handler) is running. */
    INVOKE,
    /** Interceptor body, after {@code chain.proceed()} returned. */
    POST
}

package net.nexus_flow.core.runtime.interceptors;

import java.util.HashMap;
import java.util.Map;
import net.nexus_flow.core.runtime.dispatch.DispatchChain;
import net.nexus_flow.core.runtime.dispatch.DispatchInterceptor;
import net.nexus_flow.core.runtime.dispatch.InvocationContext;
import net.nexus_flow.core.runtime.result.DispatchResult;

/**
 * Copies {@code traceId}, {@code correlationId} and {@code causationId} from the active {@link
 * InvocationContext#executionContext()} into a lightweight thread-local MDC.
 *
 * <p>The standard {@link java.lang.System.Logger} channel has no native MDC, so this interceptor
 * exposes its own {@link ThreadLocal} map and a public {@link #current()} accessor that custom
 * {@link java.lang.System.LoggerFinder LoggerFinder} backends (SLF4J-to-System.Logger,
 * Log4j2-to-System.Logger, plain JUL) can read. The runtime ships no formatter — the demo (and any
 * consumer) is expected to read the map and append it to log records as needed.
 *
 * <p><strong>Restore semantics.</strong> The previous MDC snapshot (which may be empty) is captured
 * on entry and restored in a {@code finally} block, so a throwing inner chain still unwinds with a
 * clean MDC. Verified by {@code MdcDispatchInterceptorRestoresOnErrorTest}.
 *
 * <p>Thread-safety: each thread owns its own MDC map.
 */
public final class MdcDispatchInterceptor implements DispatchInterceptor {

    /** Keys written by this interceptor. */
    public static final String TRACE_ID_KEY = "traceId";

    public static final String CORRELATION_ID_KEY = "correlationId";
    public static final String CAUSATION_ID_KEY   = "causationId";

    private static final ThreadLocal<Map<String, String>> MDC = ThreadLocal.withInitial(HashMap::new);

    /** Read-only view of the current thread's MDC. */
    public static Map<String, String> current() {
        return Map.copyOf(MDC.get());
    }

    @Override
    public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
        Map<String, String> current = MDC.get();
        // Skip the snapshot allocation on the common case where the MDC is empty (no outer
        // interceptor stamped a baggage entry). The restore path then resets to empty without
        // touching a backing map.
        boolean             wasEmpty = current.isEmpty();
        Map<String, String> previous = wasEmpty ? null : new HashMap<>(current);
        current.put(TRACE_ID_KEY, String.valueOf(ctx.executionContext().traceId()));
        current.put(CORRELATION_ID_KEY, String.valueOf(ctx.executionContext().correlationId()));
        current.put(CAUSATION_ID_KEY, String.valueOf(ctx.executionContext().causationId()));
        try {
            return chain.proceed();
        } finally {
            // Restore in place — even an interceptor failure between
            // proceed() and here unwinds with a clean MDC.
            current.clear();
            if (previous != null) {
                current.putAll(previous);
            }
        }
    }
}

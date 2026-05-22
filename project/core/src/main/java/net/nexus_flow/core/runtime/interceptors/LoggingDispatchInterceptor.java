package net.nexus_flow.core.runtime.interceptors;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import net.nexus_flow.core.runtime.dispatch.DispatchChain;
import net.nexus_flow.core.runtime.dispatch.DispatchInterceptor;
import net.nexus_flow.core.runtime.dispatch.InvocationContext;
import net.nexus_flow.core.runtime.result.DispatchResult;

/**
 * Logs dispatch before/after lines on the JDK-neutral {@link java.lang.System.Logger} channel.
 *
 * <p>Shape:
 *
 * <pre>
 * dispatching kind=COMMAND traceId=… correlationId=… messageId=… payload=…
 * completed kind=COMMAND traceId=… result=Success durationMs=…
 * </pre>
 *
 * <p>Thread-safety: {@link Logger} is thread-safe; the interceptor holds no mutable state. Logging
 * defaults to {@link Level#INFO}; downstream users plug a {@link java.lang.System.LoggerFinder
 * LoggerFinder} (SLF4J-to-System.Logger, Log4j2-to-System.Logger, plain JUL backed — pick one) to
 * route records to their preferred sink.
 *
 * <p>This interceptor is designed as a reference implementation and extension point. Adapter
 * modules can subclass or compose it, or implement {@link
 * net.nexus_flow.core.runtime.dispatch.DispatchInterceptor DispatchInterceptor} directly to route
 * structured log entries to OpenTelemetry log bridges, Micrometer observation APIs, or other
 * framework-specific logging integrations.
 */
public final class LoggingDispatchInterceptor implements DispatchInterceptor {

    private static final Logger LOGGER = System.getLogger(LoggingDispatchInterceptor.class.getName());

    private final Level level;

    /**
     * Constructs a {@code LoggingDispatchInterceptor} that logs at {@link Level#INFO}.
     *
     * <p>Use this constructor when the default log level is appropriate. To use a different level
     * (e.g. {@link Level#DEBUG} for hot-path tracing), use {@link #LoggingDispatchInterceptor(Level)
     * LoggingDispatchInterceptor(Level)}.
     */
    public LoggingDispatchInterceptor() {
        this(Level.INFO);
    }

    /**
     * Constructs a {@code LoggingDispatchInterceptor} that logs at the given level.
     *
     * @param level the {@link Level} at which dispatch before/after lines are emitted; must not be
     *              {@code null}. Use {@link Level#DEBUG} or {@link Level#TRACE} to suppress logging in
     *              production while keeping it available under a debug-level filter. Use {@link Level#WARNING}
     *              or higher only for exceptional dispatch paths.
     */
    public LoggingDispatchInterceptor(Level level) {
        this.level = level;
    }

    /**
     * Logs a {@code "dispatching"} line before proceeding down the chain and a {@code "completed"}
     * line after the chain returns.
     *
     * <p>Both lines are guarded with {@link Logger#isLoggable(Level)} so the string formatting is
     * skipped entirely when the configured log level is not active. The duration logged on the {@code
     * "completed"} line is the wall-clock time for the full interceptor sub-chain below this
     * interceptor, measured with {@link System#nanoTime()}.
     *
     * <p>Failures are <em>not</em> rethrown or swallowed: the method always returns the {@link
     * DispatchResult} from the chain, including {@link DispatchResult.Failure} and {@link
     * DispatchResult.PartialFailure} variants. Logging therefore does not alter dispatch semantics.
     *
     * @param ctx   the per-dispatch context providing {@link
     *              net.nexus_flow.core.runtime.dispatch.InvocationContext#kind() kind} and {@link
     *              net.nexus_flow.core.runtime.dispatch.InvocationContext#executionContext() executionContext}
     *              for correlation fields
     * @param chain the next interceptor or terminal handler in the dispatch chain
     * @param <R>   success payload type
     * @return the dispatch result returned by the downstream chain, unchanged
     */
    @Override
    public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
        long startNanos = System.nanoTime();
        if (LOGGER.isLoggable(level)) {
            LOGGER.log(
                       level,
                       () -> String.format(
                                           "dispatching kind=%s traceId=%s correlationId=%s messageId=%s payload=%s",
                                           ctx.kind(),
                                           ctx.executionContext().traceId(),
                                           ctx.executionContext().correlationId(),
                                           ctx.executionContext().messageId(),
                                           ctx.payload()));
        }
        DispatchResult<R> result = chain.proceed();
        if (LOGGER.isLoggable(level)) {
            long   durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String tag        =
                    switch (result) {
                                          case DispatchResult.Success<R> _ -> "Success";
                                          case DispatchResult.Failure<R> _ -> "Failure";
                                          case DispatchResult.PartialFailure<R> _ -> "PartialFailure";
                                          case DispatchResult.Accepted<R> _ -> "Accepted";
                                      };
            LOGGER.log(
                       level,
                       String.format(
                                     "completed kind=%s traceId=%s result=%s durationMs=%d",
                                     ctx.kind(), ctx.executionContext().traceId(), tag, durationMs));
        }
        return result;
    }
}

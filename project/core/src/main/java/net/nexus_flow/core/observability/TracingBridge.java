package net.nexus_flow.core.observability;

import java.util.Map;

/**
 * SPI — abstract tracing bridge so OpenTelemetry / Brave / Micrometer Tracing adapters can attach
 * spans to the framework's dispatch path without those libraries leaking into {@code core}.
 *
 * <p>The contract is deliberately minimal: open a span, return a {@link Span} handle that the
 * framework {@code close()}s when the operation completes (success or failure). Implementations are
 * responsible for context propagation (thread-local, scoped value, …).
 *
 * <p><strong>Adapter-module extension point.</strong> Adapter modules (OTel SDK, Brave, Micrometer
 * Tracing) implement this interface and inject the result via {@code
 * FlowRuntime.Builder#observability(Observability)}. The interface will not be sealed so adapters
 * can live outside {@code core}. The {@link Span} nested interface's {@code default} methods
 * ({@link Span#setAttribute} and {@link Span#recordException}) are stable hooks that adapters
 * should override.
 *
 * <p><strong>Thread-safety contract.</strong> All implementations must be fully thread-safe: the
 * framework invokes {@code startSpan} concurrently from multiple dispatcher and worker threads.
 *
 * <p><strong>Default.</strong> {@link #NO_OP} returns a no-op span — production deployments without
 * a tracing adapter pay zero cost.
 */
@FunctionalInterface
public interface TracingBridge {

    /**
     * Open a new span.
     *
     * <p>Implementations should make the returned span <em>current</em> on the calling thread so user
     * code inside the span boundary picks up the trace context automatically. The span must be
     * properly closed by the framework via {@link Span#close()}, typically within a
     * try-with-resources block.
     *
     * <p><strong>Concurrency:</strong> may be invoked concurrently from multiple dispatcher threads.
     *
     * @param operationName the operation name (e.g., {@code "nexus.flow.listener.invoke"}); must not
     *                      be {@code null}
     * @param attributes    semantic attributes to attach to the span; must not be {@code null}
     * @return a span handle; never {@code null}
     */
    Span startSpan(String operationName, Map<String, String> attributes);

    /**
     * Active-span handle.
     *
     * <p>{@link #close()} must always be safe to call (idempotent). The framework treats {@code Span}
     * as {@link AutoCloseable} so dispatch code can use it in try-with-resources.
     *
     * <p><strong>Concurrency:</strong> not thread-safe. The framework ensures that a span instance is
     * only accessed from the thread that created it.
     */
    @SuppressWarnings(
        "PMD.ImplicitFunctionalInterface") // Span contract intentionally pairs close() with default
    // setAttribute/recordException hooks.
    interface Span extends AutoCloseable {
        /**
         * Attach an extra attribute after the span has started.
         *
         * <p>Called by the framework to record contextual information such as error details, outcome
         * flags, or other semantic data relevant to the operation.
         *
         * @param key   the attribute key; must not be {@code null}
         * @param value the attribute value; must not be {@code null}
         */
        default void setAttribute(String key, String value) {
            /* default no-op */
        }

        /**
         * Record that the span ended in failure.
         *
         * <p>Called by the framework when the operation throws or is otherwise deemed a failure.
         * Implementations should record the exception in the span context.
         *
         * @param t the thrown exception; must not be {@code null}
         */
        default void recordException(Throwable t) {
            /* default no-op */
        }

        @Override
        void close();
    }

    /** Zero-cost default. Returned by {@link Observability#NO_OP Observability#NO_OP}. */
    TracingBridge NO_OP =
            new TracingBridge() {
                private final Span NO_OP_SPAN =
                        new Span() {
                            @Override
                            public void close() {
                                /* no-op */
                            }
                        };

                @Override
                public Span startSpan(String operationName, Map<String, String> attributes) {
                    return NO_OP_SPAN;
                }
            };
}

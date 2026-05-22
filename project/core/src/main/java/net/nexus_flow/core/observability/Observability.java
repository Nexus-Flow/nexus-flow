package net.nexus_flow.core.observability;

import java.util.Objects;

/**
 * SPI aggregator — pairs the two cross-cutting observability sinks ({@link MetricsRecorder} and
 * {@link TracingBridge}) so the runtime can pass a single value around instead of two parameters.
 *
 * <p>Adapter modules build their own {@code Observability} from their native registry (Micrometer +
 * Micrometer Tracing, OTel Meter + OTel Tracer, …) and inject it via {@code
 * FlowRuntime.Builder#observability(...)}.
 *
 * <p><strong>Thread-safety.</strong> This record is immutable (both sinks are effectively final)
 * and safe to share across threads. The delegate sinks ({@code metrics} and {@code tracing}) are
 * individually responsible for their own thread-safety contracts.
 *
 * <p><strong>Default.</strong> {@link #NO_OP} — both sinks are no-ops. Calling code can therefore
 * always assume non-{@code null} sinks without writing defensive null-checks.
 */
public record Observability(MetricsRecorder metrics, TracingBridge tracing) {

    /**
     * Canonical constructor — validates that neither sink is {@code null}.
     *
     * <p>Use {@link #NO_OP} instead of constructing with {@code MetricsRecorder.NO_OP} and {@code
     * TracingBridge.NO_OP} directly; the constant is equivalent and avoids redundant allocation.
     *
     * @param metrics the metrics sink; must not be {@code null}
     * @param tracing the tracing bridge; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public Observability {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(tracing, "tracing");
    }

    /** Zero-cost default returned when no adapter is installed. */
    public static final Observability NO_OP =
            new Observability(MetricsRecorder.NO_OP, TracingBridge.NO_OP);
}

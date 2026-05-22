package net.nexus_flow.core.observability;

import java.time.Duration;
import java.util.Map;

/**
 * Abstract metrics sink wired across the runtime (event bus, command bus, workers).
 *
 * <p>The interface is intentionally narrow so that adapter modules can map it onto Micrometer
 * {@code MeterRegistry}, OpenTelemetry {@code Meter}, Datadog {@code StatsDClient}, or any in-house
 * metrics system without pulling those vendor dependencies into {@code core}.
 *
 * <p><strong>Adapter-module extension point.</strong> Adapter modules (Micrometer,
 * OpenTelemetry/OTLP, Spring Actuator, Quarkus MicroProfile Metrics) implement this interface and
 * inject the result via {@code FlowRuntime.Builder#observability(Observability)}. The method
 * signatures and semantics are frozen; the interface will not be sealed so adapters can live
 * outside {@code core}.
 *
 * <p><strong>Thread-safety contract.</strong> All implementations must be fully thread-safe: the
 * framework invokes these methods concurrently from dispatch threads, worker pools, and async
 * fan-out paths.
 *
 * <p><strong>Naming convention.</strong> Metric names follow {@code dot.separated.lowercase}
 * (Micrometer style) so that adapters can forward them to Micrometer or OTel 1-to-1 without
 * renaming. Tag cardinality is the caller's responsibility — avoid unbounded high-cardinality tag
 * values (e.g., raw UUIDs) to prevent metric explosion in time-series databases.
 *
 * <p><strong>Default.</strong> {@link #NO_OP} discards every signal; it is a JVM class-init
 * singleton (interface static field is implicitly {@code public static final}). Production
 * deployments without an adapter pay zero cost.
 *
 * @see Observability
 * @see TracingBridge
 */
public interface MetricsRecorder {

    /**
     * Increments a counter by one.
     *
     * <p>Use for discrete, monotonically increasing events such as command dispatches, listener
     * invocations, dead-letters, or errors.
     *
     * <p><strong>Concurrency:</strong> may be invoked concurrently from multiple dispatcher threads.
     *
     * @param name the meter name ({@code dot.separated.lowercase}); must not be {@code null}
     * @param tags additional dimensions; must not be {@code null}, use an empty map when no tags
     *             apply
     */
    void incrementCounter(String name, Map<String, String> tags);

    /**
     * Records a single duration sample on a timer.
     *
     * <p>Use for latency measurements: command handling round-trip, listener execution time, event
     * fan-out duration. Adapters should accumulate samples into histogram buckets or percentile
     * estimators rather than storing raw values.
     *
     * <p><strong>Concurrency:</strong> may be invoked concurrently from multiple dispatcher threads.
     *
     * @param name     the meter name ({@code dot.separated.lowercase}); must not be {@code null}
     * @param duration the measured duration; must not be {@code null}
     * @param tags     additional dimensions; must not be {@code null}
     */
    void recordTimer(String name, Duration duration, Map<String, String> tags);

    /**
     * Records a point-in-time integral gauge value.
     *
     * <p>Use for current-state readings: queue depth, in-flight command count, registered listener
     * count, thread-pool active threads. Unlike counters and timers, gauge values replace the
     * previous reading rather than accumulating.
     *
     * <p><strong>Concurrency:</strong> may be invoked concurrently from multiple dispatcher threads.
     *
     * @param name  the meter name ({@code dot.separated.lowercase}); must not be {@code null}
     * @param value the current value (negative values are legal)
     * @param tags  additional dimensions; must not be {@code null}
     */
    void recordGauge(String name, long value, Map<String, String> tags);

    /**
     * Zero-cost singleton that discards every signal.
     *
     * <p>Returned by {@link Observability#NO_OP} so that every call site can unconditionally invoke
     * recorder methods without a null-check. Being an interface static field this instance is
     * initialized exactly once at class-load time and is safely published via the JVM
     * class-initialization guarantee.
     */
    MetricsRecorder NO_OP =
            new MetricsRecorder() {
                @Override
                public void incrementCounter(String name, Map<String, String> tags) {
                    /* no-op */
                }

                @Override
                public void recordTimer(String name, Duration duration, Map<String, String> tags) {
                    /* no-op */
                }

                @Override
                public void recordGauge(String name, long value, Map<String, String> tags) {
                    /* no-op */
                }
            };
}

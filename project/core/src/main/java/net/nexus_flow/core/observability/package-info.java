/**
 * Observability SPI — the pluggable metrics and tracing seam, plus the always-on JFR sub-package.
 *
 * <h2>SPI surface</h2>
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.observability.MetricsRecorder} — counter / timer / gauge sink.
 * Adapter modules implement this interface against Micrometer {@code MeterRegistry},
 * OpenTelemetry {@code Meter}, Datadog {@code StatsDClient}, etc.
 * <li>{@link net.nexus_flow.core.observability.TracingBridge} — span lifecycle (open / close)
 * with context propagation. Adapters implement this against OTel {@code Tracer}, Brave {@code
 *       Tracer}, or Micrometer Tracing.
 * <li>{@link net.nexus_flow.core.observability.Observability} — value-type aggregator carrying
 * both sinks; passed once to {@code FlowRuntime.Builder#observability(...)} at startup.
 * </ul>
 *
 * <h2>How adapters plug in</h2>
 *
 * Adapter modules live outside {@code core} so that their vendor dependencies (Micrometer, OTel
 * SDK, Datadog agent) never leak into the framework kernel. An adapter:
 *
 * <ol>
 * <li>Implements {@link net.nexus_flow.core.observability.MetricsRecorder} and/or {@link
 * net.nexus_flow.core.observability.TracingBridge}, delegating each call to the native
 * registry.
 * <li>Constructs an {@link net.nexus_flow.core.observability.Observability} from the two sinks
 * and passes it to the runtime builder.
 * <li>Spring / Quarkus / Micronaut adapters expose the {@code Observability} bean via their
 * respective DI containers so that auto-configuration can wire it automatically.
 * </ol>
 *
 * <h2>Why not {@code DispatchInterceptor}?</h2>
 *
 * Interceptors wrap only the synchronous command/query dispatch path. Listener-level concerns —
 * retries, dead-letter routing, rate-limit drops, deduplicator hits, parallel fan-out timing —
 * occur <em>inside</em> {@code ListenerExecutor}, below the interceptor onion. The observability
 * SPI is the designed hook for those signals.
 *
 * <h2>Zero-cost default</h2>
 *
 * Both sinks default to {@link net.nexus_flow.core.observability.Observability#NO_OP}, so every
 * runtime component can assume non-{@code null} sinks without defensive null-checks. Deployments
 * without an adapter pay no cost.
 *
 * <h2>JFR vs SPI</h2>
 *
 * The {@link net.nexus_flow.core.observability.jfr} sub-package contains JDK Flight Recorder custom
 * events emitted directly by {@code core}. JFR events are <em>always-on</em> structural hooks: they
 * have zero overhead when no recording is active (the JVM skips allocation and commit) and provide
 * machine-readable flight data without any adapter. The SPI ({@code MetricsRecorder} / {@code
 * TracingBridge}) is the <em>pluggable seam</em> that routes live signals to external systems such
 * as Prometheus, Zipkin, or Datadog. Both mechanisms are complementary: JFR for post-hoc
 * diagnostics and profiling, SPI for real-time alerting and distributed tracing.
 */
@NullMarked
package net.nexus_flow.core.observability;

import org.jspecify.annotations.NullMarked;

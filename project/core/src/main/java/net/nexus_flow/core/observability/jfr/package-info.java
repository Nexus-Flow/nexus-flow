/**
 * JFR custom events emitted by the {@code core} runtime on every command/event dispatch path.
 *
 * <h2>What this sub-package contains</h2>
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.observability.jfr.CommandDispatchEvent} — one event per {@code
 *       CommandBus.dispatchAndReturnResult} call.
 * <li>{@link net.nexus_flow.core.observability.jfr.EventPublishEvent} — one event per {@code
 *       EventBus.dispatchResult} call (covers both sequential and parallel fan-out paths).
 * <li>{@link net.nexus_flow.core.observability.jfr.HandlerInvokeEvent} — one event per individual
 * listener invocation within a dispatch.
 * </ul>
 *
 * <h2>JFR vs the SPI</h2>
 *
 * These events are <em>always-on</em> structural hooks built on {@code jdk.jfr.Event}. When no JFR
 * recording is active the JVM skips both the allocation and the {@link jdk.jfr.Event#commit()}
 * write, making the overhead negligible on the hot dispatch path. They provide machine-readable
 * flight data for post-hoc profiling and diagnostics without requiring any adapter module.
 *
 * <p>The pluggable {@link net.nexus_flow.core.observability.MetricsRecorder} / {@link
 * net.nexus_flow.core.observability.TracingBridge} SPI serves a different purpose: it routes
 * <em>live</em> signals to external systems such as Prometheus, Grafana, Zipkin, or Datadog. Use
 * JFR for post-hoc diagnostics; use the SPI for real-time alerting and distributed tracing.
 *
 * <h2>Instrumentation pattern</h2>
 *
 * Instrumentation sites in {@code core} follow this lock-free pattern to keep the commit path off
 * the GC and scheduler hot paths:
 *
 * <pre>{@code
 * var evt = new CommandDispatchEvent();
 * evt.begin();
 * try {
 * // ... dispatch work ...
 * evt.outcome = "Success";
 * } catch (Exception ex) {
 * evt.outcome = "Failure";
 * evt.failureClass = ex.getClass().getName();
 * } finally {
 * if (evt.shouldCommit()) {
 * evt.commandType = command.body().getClass().getName();
 * evt.commit();
 * }
 * }
 * }</pre>
 *
 * String fields are populated only after {@link jdk.jfr.Event#shouldCommit()} returns {@code true}
 * to avoid unnecessary string allocations when JFR is inactive or the event's threshold has not
 * been met.
 *
 * <h2>Zero external dependencies</h2>
 *
 * {@code jdk.jfr} ships with the JDK; no additional compile-time or runtime dependencies are
 * required.
 */
@NullMarked
package net.nexus_flow.core.observability.jfr;

import org.jspecify.annotations.NullMarked;

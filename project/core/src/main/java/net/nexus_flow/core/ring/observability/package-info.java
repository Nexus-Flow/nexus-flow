/**
 * Observability facade and JFR events for the ring transport / dispatch / saga / outbox
 * layers. Sits between the ring code and the framework's {@link
 * net.nexus_flow.core.observability.MetricsRecorder} / {@link
 * net.nexus_flow.core.observability.TracingBridge} SPIs.
 *
 * <h2>What lives here</h2>
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.ring.observability.RingMetrics} — typed facade with frozen
 * metric names and bounded-cardinality labels.
 * <li>{@link net.nexus_flow.core.ring.observability.RingJfr} — {@link jdk.jfr.Event}-derived
 * events specifically for ring-layer flight data (connections, frames, dispatches,
 * lease transitions, outbox fan-out).
 * </ul>
 *
 * <h2>Why a separate sub-package</h2>
 *
 * The framework's {@code observability} package contains generic dispatch instrumentation
 * (CommandDispatch, EventPublish, HandlerInvoke). Ring-layer instrumentation has different
 * shape (transport, frames, peers, lease ownership) and would clutter that package. Keeping
 * the ring's own observability adjacent to the ring code that emits it makes the wiring
 * traceable.
 */
@org.jspecify.annotations.NullMarked
package net.nexus_flow.core.ring.observability;

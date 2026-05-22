/**
 * Result types and error model for the runtime dispatch pipeline.
 *
 * <p>Carved out from the flat {@code runtime} package. This package collects every type that can
 * come out of a dispatch — the success/error envelopes returned by the buses and the small family
 * of runtime exceptions that the framework throws for flow-level control conditions (cancellation,
 * deadline, interruption). Keeping them together makes it obvious to adapter authors that the
 * result side of the API is a closed set that does not depend on the orchestration machinery.
 *
 * <p>Public API surface:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.runtime.result.CommandResult} — sealed sum type returned by
 * every command-bus dispatch ({@code Success} / {@code Failure}).
 * <li>{@link net.nexus_flow.core.runtime.result.DispatchResult} — fan-out envelope used by the
 * event bus and the multi-handler command paths; carries per-target outcomes.
 * <li>{@link net.nexus_flow.core.runtime.result.FlowError} — normalised error description carried
 * by {@code Failure} variants. Adapters (OTel, Micrometer) read its category to drive metrics
 * and spans without unwrapping the underlying {@link java.lang.Throwable}.
 * <li>{@link net.nexus_flow.core.runtime.result.FlowCancellationException}, {@link
 * net.nexus_flow.core.runtime.result.FlowDeadlineExceededException}, {@link
 * net.nexus_flow.core.runtime.result.FlowInterruptedException} — the three control exceptions
 * the runtime translates into {@code Failure} variants when the caller passes an {@link
 * net.nexus_flow.core.runtime.ErrorPolicy} that requests exception surfacing instead of
 * envelope conversion.
 * </ul>
 *
 * <p>None of these types are sealed against pattern-matching switches in downstream code; the
 * {@code sealed} qualifier on {@code CommandResult} and {@code DispatchResult} guarantees
 * exhaustiveness for adapter authors. Their wire shape is part of the SPI compatibility contract.
 */
@org.jspecify.annotations.NullMarked
package net.nexus_flow.core.runtime.result;

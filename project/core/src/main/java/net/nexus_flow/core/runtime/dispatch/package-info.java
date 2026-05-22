/**
 * Dispatch pipeline: the interceptor chain and its supporting synchronous executor.
 *
 * <p>Carved out from the flat {@code runtime} package. This package owns the contracts and the
 * machinery that fire on every single dispatch:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.runtime.dispatch.DispatchInterceptor} — the sealed extension
 * point used by OTel, Micrometer, MDC, and the timing reference implementation. All
 * third-party adapter observability bolts go through this contract.
 * <li>{@link net.nexus_flow.core.runtime.dispatch.DispatchChain} — immutable ordered list of
 * interceptors. Resolution is copy-on-write so an in-flight dispatch keeps using the chain it
 * was started with.
 * <li>{@link net.nexus_flow.core.runtime.dispatch.InvocationContext} — mutable per-dispatch
 * carrier handed to every interceptor in the chain. Attributes added by outer interceptors
 * remain visible to inner ones and to the caller after unwind.
 * <li>{@link net.nexus_flow.core.runtime.dispatch.InvocationKind} and {@link
 * net.nexus_flow.core.runtime.dispatch.InvocationStage} — coarse discriminators that adapter
 * code uses to gate behaviour (e.g. "metric a query but not its eventual-consistency
 * follow-up event").
 * <li>{@link net.nexus_flow.core.runtime.dispatch.SyncDispatcher} — reference synchronous
 * executor for command and query buses; owns the structured-concurrency scope used by
 * fan-out.
 * <li>{@link net.nexus_flow.core.runtime.dispatch.HandlerConcurrencyGate} — per-handler
 * bounded-concurrency primitive shared by command, event, and query buses.
 * </ul>
 *
 * <p>The split keeps {@code runtime} root focused on lifecycle and orchestration entry points
 * ({@code FlowRuntime}, {@code FlowScope}, {@code ExecutionStrategy*}, {@code PerRuntime}) and
 * prevents adapter authors from having to scan a flat 28-file package to find the interceptor SPI.
 */
@org.jspecify.annotations.NullMarked
package net.nexus_flow.core.runtime.dispatch;

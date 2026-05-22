/**
 * Runtime correlation identifiers.
 *
 * <p>Carved out from the flat {@code runtime} package. The four identifiers in this package travel
 * with every command, query, and event through the dispatch chain and are persisted in outbox/inbox
 * records to support exactly-once delivery, distributed tracing, and causal-history reconstruction.
 *
 * <p>The previous flat layout co-located orchestration entry points ({@code FlowRuntime}, {@code
 * SyncDispatcher}), identifier value types, result types, and error model in a single 28-file
 * package; the carve-out is purely organisational and does not change the on-wire format of any
 * identifier.
 *
 * <p>Public API surface:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.runtime.ids.MessageId} — globally unique identifier of an
 * individual command/query/event, also used as the outbox/inbox idempotency key.
 * <li>{@link net.nexus_flow.core.runtime.ids.CorrelationId} — caller-supplied identifier that
 * survives every fan-out, used by observability integrations to group every flow that belongs
 * to the same logical user-request.
 * <li>{@link net.nexus_flow.core.runtime.ids.CausationId} — identifier of the immediate cause of
 * a flow ({@code MessageId} of the command that produced this event, or of the query that
 * produced this command).
 * <li>{@link net.nexus_flow.core.runtime.ids.TraceId} — W3C trace-context compatible 16-byte
 * identifier that the OTel adapter maps directly to its own {@code TraceId}.
 * </ul>
 *
 * <p>All four identifiers are immutable value types with deterministic {@code equals}/{@code
 * hashCode} so they can serve as routing keys and map keys throughout the framework.
 */
@org.jspecify.annotations.NullMarked
package net.nexus_flow.core.runtime.ids;

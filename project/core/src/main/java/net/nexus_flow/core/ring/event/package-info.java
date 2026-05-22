/**
 * Ring event fan-out — wire envelope and codec for domain events crossing peer boundaries.
 *
 * <h2>Why a dedicated envelope</h2>
 *
 * A domain event is a typed payload + metadata (trace id, correlation id, causation id, tenant
 * id, sequence number on the source aggregate, etc.). When that event crosses a peer boundary,
 * the receiver also needs to know which peer SENT it and at which outbox row — so when the
 * connection drops and resumes, the receiver can ask the sender to replay events since the
 * last seen row (Debezium-style replay; lands in R5b once the {@link
 * net.nexus_flow.core.outbox.OutboxStorage} integration wires up).
 *
 * <p>{@link net.nexus_flow.core.ring.event.RingEventEnvelope} carries:
 *
 * <ul>
 * <li>{@code sourcePeerId} — the originating peer. Used by recipients to maintain a per-peer
 * cursor for resume-on-reconnect.
 * <li>{@code sourceOutboxSequence} — monotonically-increasing per-source-peer sequence. The
 * wire protocol does not require gap-freeness within the same connection (TCP guarantees
 * in-order delivery), but DOES require gap-freeness across reconnects so the recipient
 * can detect "I'm missing events 7-12, please replay".
 * <li>{@code payloadType} — FQN of the domain event class. The recipient uses this to pick
 * the right codec (R1's multi-codec routing via {@code OutboxPayloadCodecRegistry} —
 * same mechanism reused here).
 * <li>{@code codecId} — which payload codec produced the bytes. Mirrors the outbox row's
 * {@code codecId} so receivers can route to the right decoder during wire-format
 * migrations.
 * <li>{@code traceId / correlationId / causationId / tenantId} — observability propagation;
 * the receiver restores them into the {@link
 * net.nexus_flow.core.runtime.ExecutionContext} of the dispatch.
 * <li>{@code payloadBytes} — the event body as produced by the source's
 * {@link net.nexus_flow.core.outbox.OutboxPayloadCodec}.
 * </ul>
 *
 * <h2>Wire format (body of an EVENT frame)</h2>
 *
 * Length-prefixed and bounded — same defensive-cap discipline as the handshake payloads in
 * {@link net.nexus_flow.core.ring.wire} (UTF-8 strings capped by {@link
 * net.nexus_flow.core.ring.wire.RingProtocol#MAX_TYPE_NAME_BYTES} for type names, by {@link
 * net.nexus_flow.core.ring.wire.RingProtocol#MAX_PEER_ID_BYTES} for the source peer id).
 */
package net.nexus_flow.core.ring.event;

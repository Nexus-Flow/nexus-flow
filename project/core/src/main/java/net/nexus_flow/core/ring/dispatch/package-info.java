/**
 * Cross-pod command/query dispatch — request/response wire envelopes and the correlation
 * registry that ties an outbound request to its inbound reply.
 *
 * <h2>Routing flow (R7b integration)</h2>
 *
 * <ol>
 * <li>Caller dispatches a command / query against the runtime.
 * <li>The ring-aware dispatcher consults {@link
 * net.nexus_flow.core.ring.registry.HandlerDirectory} for the target type. If the local
 * peer handles it, fast-path through the local bus (no ring traffic).
 * <li>Otherwise it picks a remote peer via {@link
 * net.nexus_flow.core.ring.registry.PeerSelector}, builds a {@link
 * net.nexus_flow.core.ring.dispatch.DispatchRequestEnvelope} with a fresh
 * {@link net.nexus_flow.core.ring.dispatch.DispatchCorrelationId}, registers a pending
 * {@link java.util.concurrent.CompletableFuture} in {@link
 * net.nexus_flow.core.ring.dispatch.PendingResponseRegistry}, and sends the request
 * frame to the target peer's connection.
 * <li>The target peer's frame handler decodes the request, dispatches locally, encodes the
 * outcome into a {@link net.nexus_flow.core.ring.dispatch.DispatchResponseEnvelope}, and
 * sends it back tagged with the same correlation id.
 * <li>The originating peer's frame handler looks up the correlation in the registry and
 * completes the future with the decoded outcome.
 * </ol>
 *
 * <h2>Wire scope of this package</h2>
 *
 * Only the envelopes + correlation registry — the actual dispatcher implementation
 * (R7b RingDispatcher) lands separately and depends on the runtime's CommandBus/QueryBus,
 * the OutboxPayloadCodec for command bodies, and the membership-driven directory updates.
 * Keeping the wire layer self-contained lets adapters substitute their own codec or routing
 * policy without rebuilding the protocol.
 */
package net.nexus_flow.core.ring.dispatch;

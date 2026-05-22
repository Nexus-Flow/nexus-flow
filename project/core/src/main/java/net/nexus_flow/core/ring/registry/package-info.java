/**
 * Distributed handler-location directory + peer-selection policies. Answers "which peer(s) in
 * the ring handle commands / queries of type X" so the cross-pod routing layer (R7) can pick a
 * target without each pod statically configuring every peer's handler list.
 *
 * <h2>How the directory gets populated</h2>
 *
 * The handshake's {@link net.nexus_flow.core.ring.wire.HelloPayload} carries
 * {@code commandTypes} + {@code queryTypes} + {@code eventTypes} maps from
 * {@code typeName → fingerprint}. On {@code PeerJoined}, the membership integration (lands in
 * R7 alongside the routing) calls {@link
 * net.nexus_flow.core.ring.registry.DefaultHandlerDirectory#register
 * directory.register(peerId, role, typeNames)} to populate the index. On {@code PeerLeft} the
 * integration calls {@link
 * net.nexus_flow.core.ring.registry.DefaultHandlerDirectory#unregister
 * directory.unregister(peerId)} to drop every entry for that peer.
 *
 * <h2>Selection policies</h2>
 *
 * Given a non-empty set of candidate peers (every peer that advertises the requested type),
 * a {@link net.nexus_flow.core.ring.registry.PeerSelector} picks one for dispatch. The
 * framework ships:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.ring.registry.RoundRobinPeerSelector} — fair distribution
 * across all candidates. Default for stateless handlers.
 * <li>{@code HashRingPeerSelector} (future) — consistent hash over a routing key (typically
 * aggregate id), so the same aggregate routes to the same peer for cache affinity and
 * saga ownership stability.
 * <li>{@code LocalFirstPeerSelector} (future) — prefer the local peer when it's in the
 * candidate set, fall back to a delegate for the cross-pod case.
 * </ul>
 *
 * Adapter modules can ship more (least-loaded based on Prometheus scrape, weighted-by-latency,
 * etc.). The {@code PeerSelector} interface is intentionally minimal — easier to compose, easier
 * to test in isolation.
 */
package net.nexus_flow.core.ring.registry;

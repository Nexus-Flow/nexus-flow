/**
 * Ring membership — keeps track of which peers are currently in the ring, their health, and
 * how the local pod talks to each.
 *
 * <h2>Topology is configurable</h2>
 *
 * The membership SPI deliberately abstracts the topology choice. Three in-core strategies are
 * (or will be) shipped, and adapter modules can ship more:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.ring.membership.StaticPeerListMembership} — list of
 * {@code (PeerId, PeerAddress)} from configuration, no autodiscovery. Ideal for:
 * <ul>
 * <li>Sidecar deployments (every app connects to {@code localhost:N}, sidecar handles
 * the rest of the world).
 * <li>Small fixed clusters where peer addresses are known and stable.
 * <li>Tests.
 * </ul>
 * <li>{@code SwimGossipMembership} — SWIM-style autodiscovery via direct + indirect probes
 * (R4b, future phase). For full-mesh / hash-ring topologies in K8s where peers join and
 * leave dynamically.
 * <li>{@code ExternalRegistryMembership} — peer list pulled from K8s API / Consul / etcd
 * (future adapter module).
 * </ul>
 *
 * <p>Higher layers (R5 events, R6 directory, R7 dispatch) consume the membership through the
 * pluggable {@link net.nexus_flow.core.ring.membership.MembershipRegistry} interface and do
 * not care which strategy populates it.
 *
 * <h2>Health detection</h2>
 *
 * A heartbeat failure detector (future {@code HeartbeatFailureDetector}, deferred to the
 * R4b SWIM phase) runs the {@link net.nexus_flow.core.ring.wire.FrameType#PING ping} /
 * {@link net.nexus_flow.core.ring.wire.FrameType#PONG pong} loop and drives state
 * transitions ({@code ALIVE → SUSPECT → CONFIRMED_DEAD}) into the membership registry via
 * {@link net.nexus_flow.core.ring.membership.DefaultMembershipRegistry#transition} and
 * {@link net.nexus_flow.core.ring.membership.DefaultMembershipRegistry#recordPong}. R4
 * exposes the mutation hooks; the detector wiring lands with the SWIM strategy.
 *
 * <h2>Per-peer connection ownership</h2>
 *
 * The membership owns the {@link net.nexus_flow.core.ring.transport.RingConnection} per known
 * peer. When the failure detector classifies a peer as {@code CONFIRMED_DEAD} the membership
 * closes the connection and emits {@link
 * net.nexus_flow.core.ring.membership.MembershipEvent.PeerLeft}; higher layers reacting to that
 * event (e.g. saga lease reassignment) get the peer-loss signal exactly once.
 */
package net.nexus_flow.core.ring.membership;

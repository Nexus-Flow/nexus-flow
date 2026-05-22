/**
 * Operational APIs for the ring — health checks, JFR events, persisted state.
 *
 * <h2>Health checks</h2>
 *
 * {@link net.nexus_flow.core.ring.ops.RingHealthChecker} aggregates the membership view
 * (R4), the live-connection count (R2/R3), and the pending-dispatch backlog (R7) into a
 * single {@link net.nexus_flow.core.ring.ops.RingHealthStatus}. Suitable wiring for a
 * Kubernetes liveness / readiness probe: liveness = "the ring acceptor is bound",
 * readiness = "at least one peer is ALIVE and no in-flight dispatch is over its deadline".
 *
 * <h2>JFR events</h2>
 *
 * Low-cardinality structured events ({@code NexusRingHandshake}, {@code NexusRingFrame},
 * {@code NexusRingPeerJoin}, {@code NexusRingPeerLeave}) per the
 * {@code nexus-java-network-io-lowlevel} skill §19. Operators turn them on at runtime with a
 * JFR recording and consume them via {@code jfr summary} / Mission Control. Costs are zero
 * when no recording is active.
 *
 * <h2>Persisted ring state</h2>
 *
 * {@link net.nexus_flow.core.ring.ops.PersistedRingStateStore} writes a small flat file
 * containing the peer-cursor table (per-peer last-seen outbox sequence) so the ring resumes
 * after a pod restart without replaying the entire outbox from the beginning. Format is
 * append-only with periodic snapshots — operators can rotate the file with standard log
 * rotation tools.
 *
 * <p>This package is intentionally small: it does NOT add new transport mechanisms; it lifts
 * existing state from the membership / dispatch / transport packages into operator-facing
 * surfaces. Real "disconnect" / "pause consumer" APIs live on {@link
 * net.nexus_flow.core.ring.transport.RingConnection} (close()) and
 * {@link net.nexus_flow.core.ring.transport.RingAcceptor} (shutdown / pause via the parent
 * runtime); a consolidated facade unifying both lands in a follow-up runtime-integration
 * phase outside the ring transport layer.
 */
package net.nexus_flow.core.ring.ops;

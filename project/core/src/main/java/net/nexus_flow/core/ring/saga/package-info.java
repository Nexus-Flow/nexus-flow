/**
 * Distributed saga ownership via leases. Sagas survive pod death because the ownership lease
 * has a finite TTL — when the owner stops heartbeating, any other pod can claim the saga via
 * a single-writer compare-and-set against {@link net.nexus_flow.core.saga.SagaStorage}.
 *
 * <h2>Why leases (instead of replicated state via Raft)</h2>
 *
 * Raft per saga would scale linearly with the saga count — millions of in-flight aggregates
 * would mean millions of Raft groups with their own heartbeats, log replication, and election
 * cycles. Leases trade strict consistency on the lease itself for cheap operations on the
 * saga: every read is a local lookup; writes coordinate only at lease boundaries
 * (acquisition / renewal / handoff), not on every saga state change. The single-writer
 * invariant is enforced by {@link net.nexus_flow.core.saga.SagaStorage}'s optimistic
 * concurrency (already in core today via the {@code version} field on saga state).
 *
 * <h2>Lifecycle of one saga's lease</h2>
 *
 * <ol>
 * <li>Pod A originates a saga. Local code stamps {@code ownerPeerId = A} and {@code
 *       leaseExpiresAt = now + leaseTtl} via {@link
 * net.nexus_flow.core.ring.saga.SagaLease#owned(net.nexus_flow.core.saga.SagaId,
 * net.nexus_flow.core.ring.transport.PeerId, java.time.Clock, java.time.Duration)}.
 * <li>While A is alive, the saga heartbeat broadcasts a {@link
 * net.nexus_flow.core.ring.wire.FrameType#SAGA_STATE SAGA_STATE} frame to every
 * interested peer (typically the rest of the cluster) carrying
 * {@code (sagaId, ownerPeerId=A, leaseExpiresAt=renewed)}.
 * <li>If A dies / partitions, other peers stop receiving heartbeats. Once {@code
 *       leaseExpiresAt} elapses (plus a small grace window for clock skew), any peer B can
 * send a {@link net.nexus_flow.core.ring.wire.FrameType#LEASE_REQ LEASE_REQ} for the
 * saga.
 * <li>The current authority for the saga (typically the same B, since the storage is the
 * source of truth and B owns its local view) compares-and-sets the storage's
 * {@code ownerPeerId} from {@code A → B} only if the saga's stored lease is expired.
 * If the CAS succeeds, B sends a {@link
 * net.nexus_flow.core.ring.wire.FrameType#LEASE_GRANT LEASE_GRANT} for the saga.
 * <li>B becomes the new owner; any future heartbeat A sends carrying its stale ownership
 * is rejected by the storage CAS and A learns it has been demoted.
 * </ol>
 *
 * <h2>What this package contains</h2>
 *
 * <ul>
 * <li>Wire envelopes for {@code SAGA_STATE}, {@code LEASE_REQ}, {@code LEASE_GRANT}.
 * <li>A local {@link net.nexus_flow.core.ring.saga.LeaseRegistry} that tracks every saga's
 * current ownership view (from received heartbeats) and exposes the expiry test the
 * lease-claim path uses to decide "is this saga's lease expired".
 * </ul>
 *
 * <h2>What this package does NOT contain</h2>
 *
 * The integration with {@link net.nexus_flow.core.saga.SagaStorage} (the actual CAS, the
 * background heartbeat loop, the {@code MembershipEvent.PeerLeft → fire lease-claim} reaction)
 * lands in R8b — keeping the wire layer self-contained mirrors the split used by R5, R7.
 */
package net.nexus_flow.core.ring.saga;

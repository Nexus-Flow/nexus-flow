package net.nexus_flow.core.ring.saga;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.ring.membership.MembershipEvent;
import net.nexus_flow.core.ring.membership.MembershipRegistry;
import net.nexus_flow.core.ring.observability.RingJfr;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.RingFrame;
import net.nexus_flow.core.saga.SagaId;

/**
 * Background coordinator for saga ownership leases.
 *
 * <h2>Lifecycle (audit finding #19)</h2>
 *
 * The coordinator schedules its renewal and claim ticks via {@link
 * ScheduledExecutorService}, NOT via {@code Thread.sleep} loops. This eliminates the
 * up-to-one-interval shutdown latency of the previous design and removes the interrupt-spin
 * pathology.
 *
 * <h2>Authorization (audit security finding S2)</h2>
 *
 * {@link #onSagaState(RingConnection, SagaStateEnvelope)} REJECTS envelopes whose
 * {@code ownerPeerId} does not match the sender connection's bound peer id. This closes the
 * "any authenticated peer can rewrite saga ownership" gap.
 *
 * <h2>Inbound SAGA_STATE handling</h2>
 *
 * Folds the observation into {@link LeaseRegistry}. If the announced owner is THIS peer but
 * we have no local entry, trust storage CAS authority and drop the entry.
 *
 * <h2>Inbound LEASE_REQ handling</h2>
 *
 * Replies on the same connection with {@link LeaseGrantEnvelope} (CAS succeeded) or a
 * {@link SagaStateEnvelope} pointing at the current owner (CAS lost).
 */
public final class SagaLeaseCoordinator implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SagaLeaseCoordinator.class.getName());

    private final SagaLeaseCoordinatorConfig                               config;
    private final Clock                                                    clock;
    private final LeaseRegistry                                            leaseRegistry;
    private final RingConnectionRegistry                                   connections;
    private final SagaOwnershipClaimer                                     claimer;
    private final RingMetrics                                              metrics;
    private final ConcurrentMap<SagaId, SagaLease>                         ownedLeases = new ConcurrentHashMap<>();
    private final ScheduledExecutorService                                 scheduler;
    private volatile @org.jspecify.annotations.Nullable ScheduledFuture<?> renewalTask;
    private volatile @org.jspecify.annotations.Nullable ScheduledFuture<?> claimTask;
    private volatile boolean                                               started;
    private volatile boolean                                               closed;

    public SagaLeaseCoordinator(
            SagaLeaseCoordinatorConfig config,
            Clock clock,
            LeaseRegistry leaseRegistry,
            RingConnectionRegistry connections,
            SagaOwnershipClaimer claimer,
            MembershipRegistry membership) {
        this(config, clock, leaseRegistry, connections, claimer, membership, RingMetrics.noOp());
    }

    public SagaLeaseCoordinator(
            SagaLeaseCoordinatorConfig config,
            Clock clock,
            LeaseRegistry leaseRegistry,
            RingConnectionRegistry connections,
            SagaOwnershipClaimer claimer,
            MembershipRegistry membership,
            RingMetrics metrics) {
        this.config        = Objects.requireNonNull(config, "config");
        this.clock         = Objects.requireNonNull(clock, "clock");
        this.leaseRegistry = Objects.requireNonNull(leaseRegistry, "leaseRegistry");
        this.connections   = Objects.requireNonNull(connections, "connections");
        this.claimer       = Objects.requireNonNull(claimer, "claimer");
        this.metrics       = Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(membership, "membership");
        membership.subscribe(this::onMembershipEvent);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexus-ring-saga-coord-" + config.localPeerId());
            t.setDaemon(true);
            return t;
        });
    }

    /** Start the renewal and claim scheduled tasks. Idempotent. */
    public synchronized void start() {
        if (started || closed) {
            return;
        }
        started = true;
        long renewalMs = Math.max(50L, config.renewalInterval().toMillis());
        long claimMs   = Math.max(50L, config.claimInterval().toMillis());
        renewalTask = scheduler.scheduleAtFixedRate(this::safeRenewals,
                                                    renewalMs, renewalMs, TimeUnit.MILLISECONDS);
        claimTask   = scheduler.scheduleAtFixedRate(this::safeClaim,
                                                    claimMs, claimMs, TimeUnit.MILLISECONDS);
    }

    /** Track a saga as locally-owned. */
    public void trackOwned(SagaLease lease) {
        Objects.requireNonNull(lease, "lease");
        if (!lease.ownerPeerId().equals(config.localPeerId())) {
            throw new IllegalArgumentException(
                    "trackOwned called with lease owned by " + lease.ownerPeerId()
                            + ", expected local " + config.localPeerId());
        }
        ownedLeases.put(lease.sagaId(), lease);
        leaseRegistry.observe(lease);
    }

    public void releaseOwned(SagaId sagaId) {
        Objects.requireNonNull(sagaId, "sagaId");
        ownedLeases.remove(sagaId);
        leaseRegistry.forget(sagaId);
    }

    public Collection<SagaLease> ownedSagas() {
        return java.util.List.copyOf(ownedLeases.values());
    }

    /**
     * Invoked by the frame router for every inbound SAGA_STATE.
     *
     * <p>SECURITY: rejects envelopes whose {@code ownerPeerId} does not match the sender's
     * bound peer id. mTLS authenticates the sender; this check authorizes the claim.
     * Unauthenticated senders (plain TCP test/dev) are permitted to pass — the deployment
     * already accepted that risk by disabling TLS.
     */
    public void onSagaState(RingConnection sender, SagaStateEnvelope envelope) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(envelope, "envelope");
        PeerId senderPeer = sender.peerId();
        if (senderPeer != null && !envelope.ownerPeerId().equals(senderPeer)) {
            metrics.incrementSagaOwnershipRejected(senderPeer);
            emitLeaseEvent(envelope.sagaId(), "?", envelope.ownerPeerId().value(),
                           "REJECTED_AUTHORIZATION");
            LOG.log(System.Logger.Level.WARNING,
                    () -> "saga lease coord: rejected SAGA_STATE from " + senderPeer
                            + " claiming ownership for " + envelope.ownerPeerId()
                            + " (sender != claimed owner)");
            return;
        }
        SagaLease observed = new SagaLease(
                envelope.sagaId(),
                envelope.ownerPeerId(),
                Instant.ofEpochMilli(envelope.leaseExpiryEpochMillis()));
        leaseRegistry.observe(observed);
        if (!envelope.ownerPeerId().equals(config.localPeerId()) && ownedLeases.containsKey(envelope.sagaId())) {
            ownedLeases.remove(envelope.sagaId());
        }
    }

    public void onLeaseRequest(RingConnection sender, LeaseRequestEnvelope request) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(request, "request");
        Instant           requestedExpiry = Instant.ofEpochMilli(request.requestedExpiryEpochMillis());
        LeaseClaimOutcome outcome         =
                claimer.tryClaim(request.sagaId(), request.requesterPeerId(), requestedExpiry);
        try {
            switch (outcome) {
                case LeaseClaimOutcome.Claimed claimed    -> {
                    sender.send(RingFrame.wrapping(FrameType.LEASE_GRANT,
                                                   new LeaseGrantEnvelope(
                                                           claimed.lease().sagaId(),
                                                           claimed.lease().ownerPeerId(),
                                                           claimed.lease().expiresAt().toEpochMilli())
                                                           .encode()));
                    metrics.incrementSagaLeaseTransition("?", claimed.lease().ownerPeerId().value());
                    emitLeaseEvent(claimed.lease().sagaId(), "?",
                                   claimed.lease().ownerPeerId().value(), "CLAIMED");
                }
                case LeaseClaimOutcome.AlreadyOwned owned -> {
                    SagaLease current = owned.currentLease();
                    if (current == null) {
                        return;
                    }
                    sender.send(RingFrame.wrapping(FrameType.SAGA_STATE,
                                                   new SagaStateEnvelope(
                                                           current.sagaId(),
                                                           current.ownerPeerId(),
                                                           current.expiresAt().toEpochMilli(),
                                                           0L)
                                                           .encode()));
                }
                case LeaseClaimOutcome.SagaUnknown _      -> {
                    // requester will retry with a wider broadcast
                }
            }
        } catch (RuntimeException sendFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "failed to reply to LEASE_REQ from " + sender.remoteAddress()
                            + ": " + sendFailure.getMessage());
        }
    }

    /** Public for tests that want deterministic stepping. */
    public void tickOnce() {
        safeRenewals();
        safeClaim();
    }

    private void safeRenewals() {
        try {
            broadcastRenewals();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "saga lease coord renewal tick failed: " + t.getMessage(), t);
        }
    }

    private void safeClaim() {
        try {
            scanExpiredAndClaim();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "saga lease coord claim tick failed: " + t.getMessage(), t);
        }
    }

    private void broadcastRenewals() {
        Instant newExpiry = clock.instant().plus(config.leaseTtl());
        for (SagaLease lease : ownedLeases.values()) {
            SagaLease renewed = lease.renewed(newExpiry);
            ownedLeases.put(lease.sagaId(), renewed);
            leaseRegistry.observe(renewed);
            broadcast(new SagaStateEnvelope(
                    renewed.sagaId(),
                    renewed.ownerPeerId(),
                    renewed.expiresAt().toEpochMilli(),
                    0L).encode(),
                      FrameType.SAGA_STATE);
        }
    }

    private void scanExpiredAndClaim() {
        Instant newExpiry = clock.instant().plus(config.leaseTtl());
        for (SagaLease expired : leaseRegistry.expiredLeases()) {
            if (expired.ownerPeerId().equals(config.localPeerId())) {
                continue;
            }
            LeaseClaimOutcome outcome =
                    claimer.tryClaim(expired.sagaId(), config.localPeerId(), newExpiry);
            if (outcome instanceof LeaseClaimOutcome.Claimed claimed) {
                ownedLeases.put(claimed.lease().sagaId(), claimed.lease());
                leaseRegistry.observe(claimed.lease());
                broadcast(new SagaStateEnvelope(
                        claimed.lease().sagaId(),
                        claimed.lease().ownerPeerId(),
                        claimed.lease().expiresAt().toEpochMilli(),
                        0L).encode(),
                          FrameType.SAGA_STATE);
                metrics.incrementSagaLeaseTransition(expired.ownerPeerId().value(),
                                                     claimed.lease().ownerPeerId().value());
                emitLeaseEvent(claimed.lease().sagaId(),
                               expired.ownerPeerId().value(),
                               claimed.lease().ownerPeerId().value(),
                               "CLAIMED");
            } else if (outcome instanceof LeaseClaimOutcome.AlreadyOwned owned) {
                if (owned.currentLease() != null) {
                    leaseRegistry.observe(owned.currentLease());
                }
            }
        }
    }

    private void onMembershipEvent(MembershipEvent event) {
        if (event instanceof MembershipEvent.PeerLeft left) {
            scheduler.execute(() -> {
                try {
                    scanExpiredAndClaim();
                } catch (Throwable t) {
                    LOG.log(System.Logger.Level.WARNING,
                            () -> "claim scan reacting to PeerLeft " + left.peerId()
                                    + " failed: " + t.getMessage(), t);
                }
            });
        }
    }

    private void broadcast(byte[] body, FrameType type) {
        RingFrame frame = RingFrame.wrapping(type, body);
        for (RingConnection conn : connections.connections()) {
            if (conn.isClosed()) {
                continue;
            }
            try {
                conn.send(frame);
            } catch (RuntimeException sendFailure) {
                LOG.log(System.Logger.Level.DEBUG,
                        () -> "broadcast " + type + " to " + conn.remoteAddress() + " failed: "
                                + sendFailure.getMessage());
            }
        }
    }

    private void emitLeaseEvent(SagaId sagaId, String from, String to, String outcome) {
        var ev = new RingJfr.SagaLeaseTransition();
        ev.begin();
        if (ev.shouldCommit()) {
            ev.sagaId    = sagaId.toString();
            ev.fromOwner = from;
            ev.toOwner   = to;
            ev.outcome   = outcome;
            ev.commit();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ScheduledFuture<?> r = renewalTask;
        if (r != null) {
            r.cancel(false);
        }
        ScheduledFuture<?> c = claimTask;
        if (c != null) {
            c.cancel(false);
        }
        scheduler.shutdownNow();
    }

    /** Legacy alias for old callers; identical to {@link #close()}. */
    public void shutdown() {
        close();
    }
}

package net.nexus_flow.core.ring.membership;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.RingFrame;

/**
 * Drives per-peer liveness via PING/PONG heartbeats.
 *
 * <h2>Lifecycle (audit finding #19)</h2>
 *
 * Probe ticks are scheduled via {@link ScheduledExecutorService}, not by a {@code
 * Thread.sleep} loop. {@link #close()} cancels the scheduled future and shuts down the
 * executor without any per-tick wait.
 *
 * <h2>State machine</h2>
 *
 * <pre>
 * ALIVE --[N consecutive missed PONGs]--&gt; SUSPECT
 * SUSPECT --[PONG received]--&gt; ALIVE
 * SUSPECT --[T elapsed in SUSPECT]--&gt; CONFIRMED_DEAD
 * CONFIRMED_DEAD --[fresh PONG via reconnect]--&gt; ALIVE
 * </pre>
 */
public final class HeartbeatFailureDetector implements AutoCloseable {

    private static final System.Logger LOG =
            System.getLogger(HeartbeatFailureDetector.class.getName());

    private final HeartbeatConfig                                          config;
    private final Clock                                                    clock;
    private final RingConnectionRegistry                                   connections;
    private final DefaultMembershipRegistry                                membership;
    private final AtomicLong                                               sequence          = new AtomicLong();
    private final ConcurrentHashMap<PeerId, Integer>                       outstandingProbes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PeerId, Instant>                       suspectSince      = new ConcurrentHashMap<>();
    private final ScheduledExecutorService                                 scheduler;
    private volatile @org.jspecify.annotations.Nullable ScheduledFuture<?> probeTask;
    private volatile boolean                                               started;
    private volatile boolean                                               closed;

    public HeartbeatFailureDetector(
            HeartbeatConfig config,
            Clock clock,
            RingConnectionRegistry connections,
            DefaultMembershipRegistry membership) {
        this.config      = Objects.requireNonNull(config, "config");
        this.clock       = Objects.requireNonNull(clock, "clock");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.membership  = Objects.requireNonNull(membership, "membership");
        this.scheduler   = Executors.newSingleThreadScheduledExecutor(r -> {
                             Thread t = new Thread(r, "nexus-ring-heartbeat-" + config.localPeerId());
                             t.setDaemon(true);
                             return t;
                         });
    }

    public synchronized void start() {
        if (started || closed) {
            return;
        }
        started = true;
        long ms = Math.max(50L, config.probeInterval().toMillis());
        probeTask = scheduler.scheduleAtFixedRate(this::safeTick, ms, ms, TimeUnit.MILLISECONDS);
    }

    /**
     * Inbound PONG callback. Clears outstanding probe count for {@code sender} and recovers
     * the peer from SUSPECT to ALIVE if applicable.
     */
    public void onPong(PingPongPayload payload) {
        Objects.requireNonNull(payload, "payload");
        PeerId sender = payload.senderPeerId();
        outstandingProbes.remove(sender);
        suspectSince.remove(sender);
        membership.recordPong(sender);
    }

    /** Inbound PING callback. Replies with PONG carrying the same sequence. */
    public void onPing(RingConnection sender, PingPongPayload payload) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(payload, "payload");
        try {
            sender.send(RingFrame.wrapping(FrameType.PONG,
                                           new PingPongPayload(payload.sequence(), config.localPeerId()).encode()));
        } catch (RuntimeException sendFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "failed to reply PONG to " + sender.remoteAddress()
                            + ": " + sendFailure.getMessage());
        }
    }

    public java.util.Map<PeerId, Integer> outstandingProbesSnapshot() {
        return java.util.Map.copyOf(outstandingProbes);
    }

    /** Public for tests that want deterministic stepping. */
    public void tickOnce() {
        Instant now = clock.instant();
        // peerIdsView returns the weakly-consistent live keySet; concurrent registration is
        // safe to observe mid-tick and the snapshot allocation that peerIds() did per tick is
        // gone.
        for (PeerId peerId : connections.peerIdsView()) {
            tickOnePeer(peerId, now);
        }
    }

    private void safeTick() {
        try {
            tickOnce();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "HeartbeatFailureDetector tick failed: " + t.getMessage(), t);
        }
    }

    private void tickOnePeer(PeerId peerId, Instant now) {
        RingConnection conn = connections.get(peerId).orElse(null);
        if (conn == null || conn.isClosed()) {
            return;
        }
        try {
            long seq = sequence.incrementAndGet();
            conn.send(RingFrame.wrapping(FrameType.PING,
                                         new PingPongPayload(seq, config.localPeerId()).encode()));
        } catch (RuntimeException sendFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "ping send to " + peerId + " failed: " + sendFailure.getMessage());
        }
        int missed = outstandingProbes.merge(peerId, 1, Integer::sum);
        if (missed >= config.missesUntilSuspect()) {
            transitionToSuspectIfNeeded(peerId, now);
        }
        if (suspectSince.containsKey(peerId)) {
            Instant since = suspectSince.get(peerId);
            if (since != null && Duration.between(since, now).compareTo(config.suspectGrace()) > 0) {
                transitionToDead(peerId);
            }
        }
    }

    private void transitionToSuspectIfNeeded(PeerId peerId, Instant now) {
        var info = membership.peer(peerId).orElse(null);
        if (info == null) {
            return;
        }
        if (info.state() == PeerState.ALIVE) {
            membership.transition(peerId, PeerState.SUSPECT);
            suspectSince.put(peerId, now);
        } else if (info.state() == PeerState.SUSPECT) {
            suspectSince.putIfAbsent(peerId, now);
        }
    }

    private void transitionToDead(PeerId peerId) {
        try {
            membership.transition(peerId, PeerState.CONFIRMED_DEAD);
        } catch (IllegalArgumentException ignored) {
            // concurrent unregister — fine
        }
        suspectSince.remove(peerId);
        outstandingProbes.remove(peerId);
        RingConnection conn = connections.unregister(peerId);
        if (conn != null) {
            conn.close(new java.io.IOException("heartbeat declared peer dead"));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ScheduledFuture<?> t = probeTask;
        if (t != null) {
            t.cancel(false);
        }
        scheduler.shutdownNow();
    }

    /** Legacy alias. */
    public void shutdown() {
        close();
    }
}

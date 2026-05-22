package net.nexus_flow.core.ring.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.outbox.OutboxRecord;
import net.nexus_flow.core.outbox.OutboxStorage;
import net.nexus_flow.core.outbox.OutboxWorker;
import net.nexus_flow.core.ring.membership.MembershipEvent;
import net.nexus_flow.core.ring.membership.MembershipRegistry;
import net.nexus_flow.core.ring.observability.RingJfr;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.RingFrame;

/**
 * Outbox → ring fan-out.
 *
 * <h2>Mutual exclusion with OutboxWorker (audit finding M3)</h2>
 *
 * The bridge is gated by an explicit {@link OutboxOwnership} declaration. It refuses to
 * start unless ownership is {@link OutboxOwnership#RING_BRIDGE_ONLY} or {@link
 * OutboxOwnership#RING_BRIDGE_WITH_WORKER_FAILOVER}. This closes the previous
 * documentation-only warning that "mixing them double-publishes" — silent misconfiguration is
 * now a startup error.
 *
 * <h2>Non-destructive replay (audit finding #3)</h2>
 *
 * Reconnect catch-up calls {@link OutboxStorage#findSinceSequence(long, int)} (not the
 * destructive {@link OutboxStorage#claimBatch(int, Instant)}). The bridge sends EVENT
 * frames in ascending sequence order, advances the peer's cursor on each successful send,
 * and stops when the storage returns an empty batch.
 *
 * <h2>Per-peer durable cursor</h2>
 *
 * The {@link PeerCursorTracker} tracks the high-watermark sequence per peer. The bridge
 * advances the cursor only after a successful {@code send()} that the writer VT actually
 * delivered (via {@link RingConnection.SendCompletion}). Failed sends leave the cursor
 * unchanged so the next replay re-includes the row.
 *
 * <h2>Lifecycle</h2>
 *
 * Uses {@link ScheduledExecutorService} for the periodic drain tick — no {@code
 * Thread.sleep} loops; immediate clean shutdown.
 */
public final class RingOutboxBridge implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RingOutboxBridge.class.getName());

    private final PeerId                                                   localPeerId;
    private final OutboxStorage                                            storage;
    private final RingConnectionRegistry                                   connections;
    private final MembershipRegistry                                       membership;
    private final PeerCursorTracker                                        cursors;
    private final Clock                                                    clock;
    private final Duration                                                 pollInterval;
    private final int                                                      batchSize;
    private final OutboxOwnership                                          ownership;
    private final RingMetrics                                              metrics;
    private final ScheduledExecutorService                                 scheduler;
    private volatile @org.jspecify.annotations.Nullable ScheduledFuture<?> drainTask;
    /**
     * Worker reference for the {@link OutboxOwnership#RING_BRIDGE_WITH_WORKER_FAILOVER} mode.
     * Set via {@link #attachOutboxWorker(OutboxWorker)} BEFORE
     * {@link #start()}; consulted at start (worker.pause()) and at close (worker.resume()).
     * Volatile because attach happens on the caller thread but start/close may run from a
     * different thread.
     */
    private volatile @org.jspecify.annotations.Nullable OutboxWorker       attachedWorker;
    private volatile boolean                                               workerPausedByBridge;
    private volatile boolean                                               started;
    private volatile boolean                                               closed;

    public RingOutboxBridge(
            PeerId localPeerId,
            OutboxStorage storage,
            RingConnectionRegistry connections,
            MembershipRegistry membership,
            PeerCursorTracker cursors,
            Clock clock,
            Duration pollInterval,
            int batchSize,
            OutboxOwnership ownership) {
        this(localPeerId,
             storage,
             connections,
             membership,
             cursors,
             clock,
             pollInterval,
             batchSize,
             ownership,
             RingMetrics.noOp());
    }

    public RingOutboxBridge(
            PeerId localPeerId,
            OutboxStorage storage,
            RingConnectionRegistry connections,
            MembershipRegistry membership,
            PeerCursorTracker cursors,
            Clock clock,
            Duration pollInterval,
            int batchSize,
            OutboxOwnership ownership,
            RingMetrics metrics) {
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId");
        this.storage     = Objects.requireNonNull(storage, "storage");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.membership  = Objects.requireNonNull(membership, "membership");
        this.cursors     = Objects.requireNonNull(cursors, "cursors");
        this.clock       = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
        this.pollInterval = pollInterval;
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1: " + batchSize);
        }
        this.batchSize = batchSize;
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.metrics   = Objects.requireNonNull(metrics, "metrics");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                           Thread t = new Thread(r, "nexus-ring-outbox-bridge-" + localPeerId);
                           t.setDaemon(true);
                           return t;
                       });
        // React to membership: on PeerLeft, drop the cursor so a rejoining peer starts
        // from a clean state — the rejoiner can opt into replay-from-sequence-0 via
        // OUTBOX_REPLAY_REQ if it wants the full history.
        membership.subscribe(this::onMembershipEvent);
    }

    /**
     * Attach an {@link OutboxWorker} for the failover handshake when
     * ownership is {@link OutboxOwnership#RING_BRIDGE_WITH_WORKER_FAILOVER}.
     *
     * <p>Lifecycle contract:
     *
     * <ol>
     * <li>{@link #start()} on the bridge calls {@code worker.pause()} so the worker becomes
     * passive while the bridge is the primary publisher.
     * <li>{@link #close()} on the bridge calls {@code worker.resume()} so the worker becomes
     * active again — the next JVM tick after the bridge stops, the worker takes over
     * and the at-least-once contract is preserved across the bridge's lifetime.
     * </ol>
     *
     * <p>Calling this on a bridge whose ownership is NOT {@code RING_BRIDGE_WITH_WORKER_FAILOVER}
     * throws {@link IllegalStateException} — the caller asked for a mode incompatible with the
     * bridge configuration.
     *
     * <p>Must be invoked BEFORE {@link #start()}. Attaching after start is rejected.
     */
    public synchronized void attachOutboxWorker(
            OutboxWorker worker) {
        Objects.requireNonNull(worker, "worker");
        if (ownership != OutboxOwnership.RING_BRIDGE_WITH_WORKER_FAILOVER) {
            throw new IllegalStateException(
                    "attachOutboxWorker requires ownership=RING_BRIDGE_WITH_WORKER_FAILOVER,"
                            + " current=" + ownership);
        }
        if (started) {
            throw new IllegalStateException(
                    "attachOutboxWorker must be called before start()");
        }
        this.attachedWorker = worker;
    }

    /** Start the periodic drain. Idempotent. Throws if ownership disallows running. */
    public synchronized void start() {
        if (started || closed) {
            return;
        }
        if (ownership == OutboxOwnership.LOCAL_WORKER_ONLY) {
            throw new IllegalStateException(
                    "RingOutboxBridge refuses to start: ownership=LOCAL_WORKER_ONLY. Set "
                            + "ownership=RING_BRIDGE_ONLY to use the bridge.");
        }
        if (ownership == OutboxOwnership.RING_BRIDGE_WITH_WORKER_FAILOVER) {
            OutboxWorker worker = attachedWorker;
            if (worker == null) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "RingOutboxBridge: ownership=RING_BRIDGE_WITH_WORKER_FAILOVER but"
                                + " no worker attached via attachOutboxWorker(...). The bridge"
                                + " behaves like RING_BRIDGE_ONLY for this run.");
            } else {
                worker.pause();
                workerPausedByBridge = true;
                LOG.log(System.Logger.Level.INFO,
                        () -> "RingOutboxBridge: paused attached OutboxWorker — bridge is now"
                                + " the primary publisher. Worker will resume on bridge close.");
            }
        }
        started = true;
        long ms = pollInterval.toMillis();
        drainTask = scheduler.scheduleWithFixedDelay(this::safeDrain,
                                                     ms, ms, TimeUnit.MILLISECONDS);
    }

    /**
     * Single authoritative drain — visible for tests / non-scheduled drivers.
     *
     * @return number of rows processed (0 if no PENDING work)
     */
    public int drainOnce() {
        if (closed) {
            return 0;
        }
        Instant            now = clock.instant();
        List<OutboxRecord> batch;
        try {
            batch = storage.claimBatch(batchSize, now);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "ring outbox bridge claimBatch failed: " + t.getMessage(), t);
            return 0;
        }
        if (batch.isEmpty()) {
            return 0;
        }
        for (OutboxRecord r : batch) {
            fanOut(r);
        }
        return batch.size();
    }

    /**
     * Non-destructive replay: scan {@code storage.findSinceSequence(sinceSequence, batchSize)}
     * and re-send every row to {@code peerId}. Returns the number of frames sent. Used by the
     * reconnect path: the peer announces its cursor; the bridge catches up without affecting
     * other peers' delivery state.
     */
    public int replayTo(PeerId peerId, long sinceSequence) {
        Objects.requireNonNull(peerId, "peerId");
        if (closed) {
            return 0;
        }
        RingConnection conn = connections.get(peerId).orElse(null);
        if (conn == null || conn.isClosed()) {
            return 0;
        }
        int  total  = 0;
        long cursor = sinceSequence;
        // Loop in bounded batches so a peer with very stale cursor doesn't dominate the
        // scheduler thread — the next drain tick continues.
        while (true) {
            List<OutboxRecord> rows;
            try {
                rows = storage.findSinceSequence(cursor, batchSize);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "ring outbox bridge replay scan failed for " + peerId + ": "
                                + t.getMessage(), t);
                return total;
            }
            if (rows.isEmpty()) {
                break;
            }
            for (OutboxRecord row : rows) {
                if (sendToPeer(conn, peerId, row)) {
                    cursors.advance(peerId, row.sequenceNo());
                    total++;
                    metrics.incrementOutboxReplay();
                } else {
                    return total;
                }
                cursor = Math.max(cursor, row.sequenceNo());
            }
            if (rows.size() < batchSize) {
                break;
            }
        }
        return total;
    }

    public Map<PeerId, Long> cursorSnapshot() {
        return cursors.snapshot();
    }

    private void safeDrain() {
        try {
            drainOnce();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "ring outbox bridge tick failed: " + t.getMessage(), t);
        }
    }

    private void fanOut(OutboxRecord row) {
        byte[] body;
        try {
            body = toEnvelope(row).encode();
        } catch (RuntimeException encodeFail) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "ring outbox bridge: failed to encode row " + row.outboxId()
                            + ": " + encodeFail.getMessage());
            // Encode failure was previously sent straight to terminal — silent loss for
            // transient failures. At-least-once: release back to PENDING so the next drain
            // retries. The OutboxWorker's backoff/maxAttempts policy is the right place to
            // detect a permanent failure (the same row keeps failing → eventually terminal).
            // The bridge itself does NOT escalate to terminal on a single encode miss.
            try {
                storage.releaseToReady(row.outboxId());
            } catch (RuntimeException ignored) {
                // already terminal or unknown — benign
            }
            return;
        }
        RingFrame                                                          frame = RingFrame.wrapping(FrameType.EVENT, body);
        java.util.Collection<net.nexus_flow.core.ring.membership.PeerInfo> alive = membership.alivePeers();
        // Pre-size the bookkeeping collections to the alive-peer count. The lists/sets are
        // populated proportionally to that count; default initial capacity (10/16) over-
        // allocates for small clusters and under-allocates (forcing rehash) for large.
        int          alivePeerCount   = alive.size();
        Set<PeerId>  deliveredTo      = HashSet.newHashSet(alivePeerCount);
        Set<PeerId>  failedFor        = HashSet.newHashSet(alivePeerCount);
        List<PeerId> attempted        = new ArrayList<>(alivePeerCount);
        int          aliveRemotePeers = 0;
        for (var peerInfo : alive) {
            if (peerInfo.peerId().equals(localPeerId)) {
                continue;
            }
            aliveRemotePeers++;
            RingConnection conn = connections.get(peerInfo.peerId()).orElse(null);
            if (conn == null || conn.isClosed()) {
                // Membership says ALIVE but the bridge has no live connection (yet/anymore).
                // This is an UNREACHABLE failure — NOT "lonely pod". Leave the row PENDING
                // so a later drain retries once the dialer / acceptor restores the link.
                failedFor.add(peerInfo.peerId());
                continue;
            }
            attempted.add(peerInfo.peerId());
            if (sendToPeer(conn, peerInfo.peerId(), frame)) {
                deliveredTo.add(peerInfo.peerId());
                cursors.advance(peerInfo.peerId(), row.sequenceNo());
            } else {
                failedFor.add(peerInfo.peerId());
            }
        }
        boolean atLeastOneDelivered = !deliveredTo.isEmpty();
        // "Lonely pod" = no alive remote peers exist in the membership. Only in that case
        // is it safe to mark PUBLISHED with zero deliveries: there is nobody to replay to.
        // If alive peers exist but the bridge could not reach any of them (no connection,
        // backpressure, send failure), the row MUST stay PENDING so a later drain retries
        // — that is the at-least-once contract.
        boolean lonelyPod = aliveRemotePeers == 0;
        if (atLeastOneDelivered || lonelyPod) {
            try {
                storage.markPublished(row.outboxId());
            } catch (RuntimeException markFail) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "ring outbox bridge: markPublished " + row.outboxId()
                                + " failed: " + markFail.getMessage());
            }
        } else {
            // Release so claimBatch can re-attempt without inflating the attempt counter.
            try {
                storage.releaseToReady(row.outboxId());
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
        emitFanOutEvent(row, deliveredTo.size(), failedFor.size(), frame.body().length());
        metrics.incrementOutboxFanout(atLeastOneDelivered || lonelyPod);
    }

    private boolean sendToPeer(RingConnection conn, PeerId peerId, OutboxRecord row) {
        return sendToPeer(conn, peerId, RingFrame.wrapping(
                                                           FrameType.EVENT, toEnvelope(row).encode()));
    }

    private boolean sendToPeer(RingConnection conn, PeerId peerId, RingFrame frame) {
        try {
            // Truthful send: rely on the connection's per-frame outcome model. We only
            // advance the cursor when the writer actually flushed the bytes.
            var future  = new java.util.concurrent.CompletableFuture<Boolean>();
            var outcome = conn.send(frame, (success, _cause) -> future.complete(success));
            return switch (outcome) {
                case ENQUEUED                                                   -> {
                    try {
                        yield future.get(5, TimeUnit.SECONDS);
                    } catch (Exception waitFail) {
                        LOG.log(System.Logger.Level.DEBUG,
                                () -> "ring outbox bridge: send to " + peerId + " wait failed: "
                                        + waitFail.getMessage());
                        yield false;
                    }
                }
                case REJECTED_BACKPRESSURE, REJECTED_CLOSED, REJECTED_HANDSHAKE -> false;
            };
        } catch (RuntimeException sendFail) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "ring outbox bridge: send to " + peerId + " failed: "
                            + sendFail.getMessage());
            return false;
        }
    }

    private RingEventEnvelope toEnvelope(OutboxRecord row) {
        String codecId  = row.codecId() == null ? "java-v1" : row.codecId();
        String tenantId = row.tenantId() == null ? null : row.tenantId().value();
        return new RingEventEnvelope(
                localPeerId,
                row.sequenceNo(),
                row.payloadType().getName(),
                codecId,
                row.traceId().value(),
                row.correlationId().value(),
                row.causationId().value(),
                tenantId,
                row.payloadBytes());
    }

    private void emitFanOutEvent(OutboxRecord row, int delivered, int failed, int payloadBytes) {
        var ev = new RingJfr.OutboxFanout();
        ev.begin();
        if (ev.shouldCommit()) {
            ev.outboxSequence = row.sequenceNo();
            ev.peersReached   = delivered;
            ev.peersFailed    = failed;
            ev.payloadBytes   = payloadBytes;
            ev.commit();
        }
    }

    private void onMembershipEvent(MembershipEvent event) {
        if (event instanceof MembershipEvent.PeerLeft left) {
            cursors.forget(left.peerId());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ScheduledFuture<?> t = drainTask;
        if (t != null) {
            t.cancel(false);
        }
        scheduler.shutdownNow();
        // Failover handshake: if we paused an OutboxWorker at start(), resume it now so the
        // worker takes over publishing. This preserves at-least-once across the bridge's
        // lifetime — every PENDING row left in the storage when the bridge closes is picked
        // up by the worker on its next tick.
        OutboxWorker worker = attachedWorker;
        if (workerPausedByBridge && worker != null) {
            workerPausedByBridge = false;
            try {
                worker.resume();
                LOG.log(System.Logger.Level.INFO,
                        () -> "RingOutboxBridge: resumed attached OutboxWorker — failover"
                                + " handshake complete, worker is the primary publisher again.");
            } catch (RuntimeException re) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "RingOutboxBridge: failed to resume attached worker on close: "
                                + re.getMessage(), re);
            }
        }
    }

    /** Legacy alias. */
    public void shutdown() {
        close();
    }
}

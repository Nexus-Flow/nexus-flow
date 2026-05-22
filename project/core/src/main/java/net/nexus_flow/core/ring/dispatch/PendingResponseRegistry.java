package net.nexus_flow.core.ring.dispatch;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Registers outbound dispatch requests so the inbound response handler can complete the
 * matching {@link CompletableFuture}.
 *
 * <h2>Per-peer index (audit findings #4 and #14)</h2>
 *
 * Every entry is tagged with the target {@link PeerId}. {@link #cancelAllForPeer(PeerId,
 * Throwable)} iterates the per-peer set and completes every future exceptionally in O(N
 * entries for that peer) — without scanning the global map. The connection-close path in
 * {@link RingDispatcher} calls this to surface peer disconnects as immediate exceptional
 * completions instead of forcing callers to wait the full timeout.
 *
 * <h2>ScheduledFuture cleanup</h2>
 *
 * Every {@link #register} both inserts the pending future AND a {@link ScheduledFuture} that
 * will fire its timeout. {@link #complete}, {@link #completeExceptionally}, and {@link
 * #cancelAllForPeer} all cancel the timeout task BEFORE returning. The registry guarantees
 * that scheduler queue depth remains proportional to in-flight requests, NOT to historical
 * throughput.
 *
 * <h2>Atomic capacity guard</h2>
 *
 * The cap is enforced via {@link AtomicInteger} counter pre-incremented in {@link #register}
 * and decremented in every removal path. {@link #inFlight()} returns this counter — O(1) and
 * lock-free, in contrast to the previous {@code ConcurrentHashMap.size()} which is O(N) on
 * some JDK versions.
 */
public final class PendingResponseRegistry implements AutoCloseable {

    private final ConcurrentHashMap<DispatchCorrelationId, Entry>       pending  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PeerId, Set<DispatchCorrelationId>> byPeer   = new ConcurrentHashMap<>();
    private final AtomicInteger                                         inFlight = new AtomicInteger();
    private final ScheduledExecutorService                              scheduler;
    private final int                                                   maxInFlight;
    private final RingMetrics                                           metrics;

    /** One pending entry: peer, future, and the timeout's ScheduledFuture handle. */
    private record Entry(
                         PeerId peer,
                         CompletableFuture<DispatchResponseEnvelope> future,
                         ScheduledFuture<?> timeoutTask) {
    }

    public PendingResponseRegistry(
            int maxInFlight, ScheduledExecutorService scheduler) {
        this(maxInFlight, scheduler, RingMetrics.noOp());
    }

    public PendingResponseRegistry(
            int maxInFlight, ScheduledExecutorService scheduler, RingMetrics metrics) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be >= 1: " + maxInFlight);
        }
        this.maxInFlight = maxInFlight;
        this.scheduler   = Objects.requireNonNull(scheduler, "scheduler");
        this.metrics     = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Register a pending request bound to {@code peer}. The returned future is completed on
     * inbound response (via {@link #complete}), on transport failure (via {@link
     * #completeExceptionally} or {@link #cancelAllForPeer}), or on timeout (via the scheduled
     * task).
     *
     * @throws IllegalStateException if the in-flight cap is reached
     */
    public CompletableFuture<DispatchResponseEnvelope> register(
            DispatchCorrelationId id, PeerId peer, Duration timeout) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        int prev = inFlight.get();
        while (prev < maxInFlight) {
            if (inFlight.compareAndSet(prev, prev + 1)) {
                break;
            }
            prev = inFlight.get();
        }
        if (prev >= maxInFlight) {
            throw new IllegalStateException(
                    "pending response registry at capacity " + maxInFlight);
        }
        CompletableFuture<DispatchResponseEnvelope> future      = new CompletableFuture<>();
        ScheduledFuture<?>                          timeoutTask = scheduler.schedule(
                                                                                     () -> fireTimeout(id, timeout),
                                                                                     timeout.toMillis(),
                                                                                     TimeUnit.MILLISECONDS);
        Entry                                       entry       = new Entry(peer, future, timeoutTask);
        Entry                                       prior       = pending.putIfAbsent(id, entry);
        if (prior != null) {
            inFlight.decrementAndGet();
            timeoutTask.cancel(false);
            throw new IllegalStateException("correlation id collision: " + id);
        }
        byPeer.computeIfAbsent(peer, _ -> ConcurrentHashMap.newKeySet()).add(id);
        return future;
    }

    /**
     * Complete the future for {@code id} with {@code response}. Returns {@code true} on
     * success, {@code false} when the entry was already removed (duplicate response, timeout
     * fired first, or cancelled by peer-close).
     */
    public boolean complete(DispatchCorrelationId id, DispatchResponseEnvelope response) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(response, "response");
        Entry entry = pending.remove(id);
        if (entry == null) {
            return false;
        }
        removeFromPeerIndex(entry.peer, id);
        entry.timeoutTask.cancel(false);
        inFlight.decrementAndGet();
        return entry.future.complete(response);
    }

    /**
     * Complete the future for {@code id} exceptionally with {@code cause}. Same return
     * semantics as {@link #complete}.
     */
    public boolean completeExceptionally(DispatchCorrelationId id, Throwable cause) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cause, "cause");
        Entry entry = pending.remove(id);
        if (entry == null) {
            return false;
        }
        removeFromPeerIndex(entry.peer, id);
        entry.timeoutTask.cancel(false);
        inFlight.decrementAndGet();
        return entry.future.completeExceptionally(cause);
    }

    /**
     * Cancel every pending entry bound to {@code peer} with the given cause. Used by {@link
     * RingDispatcher} when the connection to {@code peer} closes — without this, every
     * pending future for that peer would wait until its individual timeout fires.
     *
     * @return number of entries cancelled
     */
    public int cancelAllForPeer(PeerId peer, Throwable cause) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(cause, "cause");
        Set<DispatchCorrelationId> ids = byPeer.remove(peer);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        for (DispatchCorrelationId id : ids) {
            Entry entry = pending.remove(id);
            if (entry == null) {
                continue;
            }
            entry.timeoutTask.cancel(false);
            inFlight.decrementAndGet();
            metrics.incrementDispatchCancelled();
            if (entry.future.completeExceptionally(cause)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Current number of in-flight requests; O(1). */
    public int inFlight() {
        return inFlight.get();
    }

    /** Number of in-flight requests bound to {@code peer}; O(1). */
    public int inFlightFor(PeerId peer) {
        Set<DispatchCorrelationId> ids = byPeer.get(peer);
        return ids == null ? 0 : ids.size();
    }

    private void fireTimeout(DispatchCorrelationId id, Duration timeout) {
        Entry entry = pending.remove(id);
        if (entry == null) {
            return;
        }
        removeFromPeerIndex(entry.peer, id);
        inFlight.decrementAndGet();
        metrics.incrementDispatchTimeout();
        entry.future.completeExceptionally(
                                           new TimeoutException("no response for correlation id " + id + " within " + timeout));
    }

    private void removeFromPeerIndex(PeerId peer, DispatchCorrelationId id) {
        Set<DispatchCorrelationId> ids = byPeer.get(peer);
        if (ids == null) {
            return;
        }
        ids.remove(id);
        if (ids.isEmpty()) {
            byPeer.remove(peer, ids);
        }
    }

    @Override
    public void close() {
        Iterator<Map.Entry<DispatchCorrelationId, Entry>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<DispatchCorrelationId, Entry> e = it.next();
            e.getValue().timeoutTask.cancel(false);
            e.getValue().future.completeExceptionally(
                                                      new CancellationException(
                                                              "pending response registry closed; id " + e.getKey()));
            it.remove();
        }
        byPeer.clear();
        inFlight.set(0);
    }
}

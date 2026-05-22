package net.nexus_flow.core.ring.transport;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-source token-bucket gate guarding the accept loop. Every accept is checked against a
 * per-source bucket BEFORE the connection slot is allocated, so a single misbehaving peer
 * cannot exhaust the acceptor's capacity faster than the bucket refills.
 *
 * <h2>Algorithm</h2>
 *
 * Continuous-fill token bucket per source key:
 *
 * <ol>
 * <li>Each source has a bucket capped at {@code burst} tokens.
 * <li>Tokens refill at {@code refillPerSecond}; fractional accumulation is computed on
 * access from elapsed monotonic time, so there is no per-tick scheduler.
 * <li>{@link #tryAcquire(String)} attempts to subtract one token; succeeds iff the bucket
 * has at least one available.
 * </ol>
 *
 * <h2>Per-bucket synchronization</h2>
 *
 * Each {@link Bucket} holds its own monitor. Concurrent requests for the SAME source
 * contend on that one monitor; different sources are independent. This is the right shape
 * for accept-time gating: typical concurrency per source is low (one client at a time), so
 * the monitor is almost always uncontended.
 *
 * <h2>Map bounding</h2>
 *
 * Idle buckets are aged out via {@link #evictStale(Duration)} which the acceptor calls on a
 * slow watchdog tick. Worst-case unbounded growth is bounded by the rate at which the
 * acceptor runs the evict pass.
 */
public final class AcceptRateLimiter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private final double burst;
    private final double refillPerSecond;

    public AcceptRateLimiter(double burst, double refillPerSecond, Clock clock) {
        // {@code clock} is accepted for API symmetry with other ring components and to
        // reserve future injection points (e.g. monotonic clock simulation in tests). The
        // current implementation uses {@link System#nanoTime()} for elapsed-time accounting
        // because the rate limiter MUST be monotonic against scheduler jitter — wall-clock
        // skew is the wrong semantics for a token bucket.
        Objects.requireNonNull(clock, "clock");
        if (burst <= 0) {
            throw new IllegalArgumentException("burst must be > 0: " + burst);
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be > 0: " + refillPerSecond);
        }
        this.burst           = burst;
        this.refillPerSecond = refillPerSecond;
    }

    /** Production defaults: burst 16, refill 4 per second per source. */
    public static AcceptRateLimiter defaults(Clock clock) {
        return new AcceptRateLimiter(16, 4, clock);
    }

    /**
     * Try to acquire one token for {@code sourceKey}. Returns {@code true} on success.
     */
    public boolean tryAcquire(String sourceKey) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Bucket b = buckets.computeIfAbsent(sourceKey, _ -> new Bucket(burst));
        return b.tryAcquire(refillPerSecond, burst);
    }

    /**
     * Drop buckets that have not been touched for more than {@code idleFor}. Bounded-time
     * sweep — call from a slow scheduled task (e.g. every 60 s in production).
     *
     * @return the number of buckets evicted
     */
    public int evictStale(Duration idleFor) {
        Objects.requireNonNull(idleFor, "idleFor");
        long cutoffNanos = System.nanoTime() - idleFor.toNanos();
        int  removed     = 0;
        for (Iterator<java.util.Map.Entry<String, Bucket>> it = buckets.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (entry.getValue().lastTouchedNanos() < cutoffNanos) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Diagnostics: number of tracked source buckets. */
    public int trackedSources() {
        return buckets.size();
    }

    private static final class Bucket {

        /** Guarded by {@code this}: token balance, last refill instant. */
        private double tokens;
        private long   lastRefillNanos;

        Bucket(double initialTokens) {
            this.tokens          = initialTokens;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire(double refillPerSecond, double burst) {
            long   now          = System.nanoTime();
            long   elapsedNanos = Math.max(0L, now - lastRefillNanos);
            double refilled     = (elapsedNanos / 1_000_000_000.0) * refillPerSecond;
            tokens          = Math.min(burst, tokens + refilled);
            lastRefillNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized long lastTouchedNanos() {
            return lastRefillNanos;
        }
    }
}

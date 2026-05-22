package net.nexus_flow.core.cqrs.event;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Default in-process {@link TokenBucket} backing {@link EventListener#rateLimit()}. Thread-safe and
 * lock-free via CAS loops on two {@link AtomicLong}s: one for the running token count, one for the
 * last-refill timestamp.
 *
 * <p>Tokens are added lazily at the configured rate on each {@link #tryAcquire()} call (no
 * background thread).
 *
 * <p>This is an in-process rate limiter. For distributed rate limiting across multiple JVM
 * instances, implement {@link TokenBucket} yourself (e.g. against Bucket4j + Redis) and return it
 * from {@link EventListener#tokenBucket()}.
 *
 * <h2>Concurrency contract</h2>
 *
 * Both the {@code availableTokens} CAS loop and the {@code lastRefillNanos} CAS guard are required.
 * Pre-fix the timestamp was a plain {@code volatile} write: two concurrent {@link #refill()}
 * callers could read the same {@code previousRefill}, compute the same {@code tokensToAdd}, and
 * both apply the credit — silently doubling the effective rate under contention. The CAS on {@link
 * #lastRefillNanos} now serialises which thread "owns" the elapsed window; losers retry the loop
 * and see {@code elapsed <= 0}, contributing zero tokens.
 *
 * <p>The fixed-point arithmetic uses {@link Math#multiplyExact(long, long)} so pathological
 * configurations (very high rate × very large elapsed window) saturate at burst rather than
 * wrapping silently.
 */
final class InMemoryTokenBucket implements TokenBucket {

    /** Fixed-point scale factor: 1 token = 1000 internal units (sub-token refills accumulate). */
    private static final long FIXED_POINT_SCALE = 1000L;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int maxPerSecond;
    private final int burst;

    /** Available tokens in fixed-point units. */
    private final AtomicLong availableTokens;

    /**
     * Last-refill timestamp from {@link System#nanoTime()}. Updated atomically by the winning thread
     * on each refill cycle so concurrent callers cannot double-credit the bucket.
     */
    private final AtomicLong lastRefillNanos;

    InMemoryTokenBucket(ListenerRateLimit limit) {
        this.maxPerSecond    = limit.maxPerSecond();
        this.burst           = limit.burst();
        this.availableTokens = new AtomicLong((long) burst * FIXED_POINT_SCALE);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /**
     * Attempts to consume one token.
     *
     * @return {@code true} if a token was available; {@code false} if rate-limited.
     */
    @Override
    public boolean tryAcquire() {
        refill();
        while (true) {
            long current = availableTokens.get();
            if (current < FIXED_POINT_SCALE)
                return false;
            if (availableTokens.compareAndSet(current, current - FIXED_POINT_SCALE))
                return true;
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long previousRefill;
        long elapsed;
        do {
            previousRefill = lastRefillNanos.get();
            elapsed        = now - previousRefill;
            if (elapsed <= 0) {
                return;
            }
        } while (!lastRefillNanos.compareAndSet(previousRefill, now));

        // CAS won — this thread alone is responsible for crediting [previousRefill, now]. Losing
        // threads either retry the CAS (then see elapsed <= 0 on the next iteration) or already
        // returned early.
        long tokensToAdd;
        try {
            long scaled = Math.multiplyExact(elapsed, (long) maxPerSecond);
            scaled      = Math.multiplyExact(scaled, FIXED_POINT_SCALE);
            tokensToAdd = scaled / NANOS_PER_SECOND;
        } catch (ArithmeticException _) {
            // Pathological elapsed × rate. Saturate at burst — this is the upper bound the bucket
            // can ever hold, so any larger credit would be clamped anyway.
            tokensToAdd = (long) burst * FIXED_POINT_SCALE;
        }
        if (tokensToAdd <= 0) {
            return;
        }
        long maxTokens = (long) burst * FIXED_POINT_SCALE;
        long credit    = tokensToAdd;
        availableTokens.updateAndGet(t -> Math.min(t + credit, maxTokens));
    }
}

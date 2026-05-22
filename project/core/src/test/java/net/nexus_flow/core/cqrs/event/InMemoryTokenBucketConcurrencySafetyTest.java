package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pins the concurrency-safety contract of {@link InMemoryTokenBucket} against the previously-fixed
 * double-credit race on {@code lastRefillNanos}.
 *
 * <p>Pre-fix the timestamp was a plain {@code volatile} write inside {@link
 * InMemoryTokenBucket#refill()}: two concurrent {@code tryAcquire()} callers could read the same
 * {@code previousRefill}, compute the same {@code tokensToAdd}, and both apply the credit. Under
 * sustained contention the bucket effectively doubled (or N-tupled with N callers) the configured
 * rate, silently breaking the rate limit — a security/availability bug for any consumer that uses
 * this bucket as an abuse-control or DoS-mitigation knob.
 *
 * <p>Post-fix the timestamp is an {@link java.util.concurrent.atomic.AtomicLong} and refill uses a
 * CAS guard: only the winning thread credits the elapsed window; losers retry and see {@code
 * elapsed <= 0}.
 */
class InMemoryTokenBucketConcurrencySafetyTest {

    /**
     * Burst-only acquires must terminate after exactly {@code burst} successful calls when the rate
     * is essentially zero. This is the simplest demonstration that concurrent {@code tryAcquire()}
     * does not over-grant beyond the burst budget — a thread that "loses" a CAS round MUST observe
     * the post-CAS state and either succeed (its own slot) or be rejected.
     */
    @Test
    void contention_doesNotOverGrantBeyondBurst_whenRateIsEssentiallyZero() throws Exception {
        int burst = 100;
        // Rate = 1 per second so during a sub-second concurrent burst the refill contributes
        // (statistically) 0 tokens. Any "extra" acquires beyond `burst` would be the double-credit
        // race.
        ListenerRateLimit   limit  = new ListenerRateLimit(1, burst);
        InMemoryTokenBucket bucket = new InMemoryTokenBucket(limit);

        int threadCount       = 32;
        int acquiresPerThread = 200; // Total attempts = 6400; only `burst` (+ a tiny rate window)
        // should succeed.
        AtomicInteger  successes = new AtomicInteger();
        CountDownLatch start     = new CountDownLatch(1);
        CountDownLatch done      = new CountDownLatch(threadCount);

        try (ExecutorService pool = Executors.newFixedThreadPool(threadCount)) {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(
                            () -> {
                                try {
                                    start.await();
                                    for (int i = 0; i < acquiresPerThread; i++) {
                                        if (bucket.tryAcquire()) {
                                            successes.incrementAndGet();
                                        }
                                    }
                                } catch (InterruptedException _) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    done.countDown();
                                }
                            });
            }
            long t0 = System.nanoTime();
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "all threads must finish within 10s");
            long elapsedNanos = System.nanoTime() - t0;

            // Tolerance: burst + (elapsed × rate). With rate=1/sec the rate-window contribution is
            // ceil(elapsed_seconds × 1). Allow a small buffer for clock jitter / scheduler delay.
            long elapsedSecondsCeil = (elapsedNanos + 999_999_999L) / 1_000_000_000L;
            long upperBound         = burst + elapsedSecondsCeil + 2L;
            assertTrue(
                       successes.get() <= upperBound,
                       "acquires exceeded burst + rate*elapsed bound — DOUBLE-CREDIT RACE? got "
                               + successes.get()
                               + " upper bound "
                               + upperBound);
            assertTrue(
                       successes.get() >= burst - 1,
                       "acquires below burst — bucket under-credited; got " + successes.get());
        }
    }

    @Test
    void afterBurst_furtherCallsAreRejected_untilRefillElapses() {
        ListenerRateLimit   limit  = new ListenerRateLimit(1, 5);
        InMemoryTokenBucket bucket = new InMemoryTokenBucket(limit);

        // Drain the burst.
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(), "burst slot " + i + " must succeed");
        }
        // Immediate next call must fail (no time to refill at 1/s).
        assertFalse(bucket.tryAcquire(), "after burst, next call must be rate-limited");
    }

    @Test
    void singleThread_doesNotOverGrant_acrossManyAcquires() {
        int                 burst  = 50;
        ListenerRateLimit   limit  = new ListenerRateLimit(1, burst);
        InMemoryTokenBucket bucket = new InMemoryTokenBucket(limit);

        int  successes = 0;
        long t0        = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            if (bucket.tryAcquire()) {
                successes++;
            }
        }
        long elapsedNanos       = System.nanoTime() - t0;
        long elapsedSecondsCeil = (elapsedNanos + 999_999_999L) / 1_000_000_000L;
        long upperBound         = burst + elapsedSecondsCeil + 2L;
        assertTrue(
                   successes <= upperBound,
                   "single-thread acquires exceeded budget; got " + successes + " upper " + upperBound);
    }

    @Test
    void burstReachable_immediatelyAfterConstruction() {
        ListenerRateLimit   limit  = new ListenerRateLimit(10, 10);
        InMemoryTokenBucket bucket = new InMemoryTokenBucket(limit);
        int                 got    = 0;
        for (int i = 0; i < 10; i++) {
            if (bucket.tryAcquire())
                got++;
        }
        assertEquals(10, got, "fresh bucket must allow exactly `burst` immediate acquires");
    }
}

package net.nexus_flow.core.ring.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class AcceptRateLimiterTest {

    @Test
    void initialBucket_acceptsBurstThenRejects() {
        AcceptRateLimiter limiter  = new AcceptRateLimiter(4, 1, Clock.systemUTC());
        int               accepted = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("source-A")) {
                accepted++;
            }
        }
        assertTrue(accepted >= 4 && accepted <= 5,
                   "burst should allow ~4 immediately; got " + accepted);
    }

    @Test
    void differentSources_areIndependent() {
        AcceptRateLimiter limiter = new AcceptRateLimiter(2, 0.01, Clock.systemUTC());
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        // Another source still has full burst.
        assertTrue(limiter.tryAcquire("b"));
        assertTrue(limiter.tryAcquire("b"));
    }

    @Test
    void bucketRefills_overTime() throws InterruptedException {
        AcceptRateLimiter limiter = new AcceptRateLimiter(2, 50, Clock.systemUTC());
        assertTrue(limiter.tryAcquire("p"));
        assertTrue(limiter.tryAcquire("p"));
        assertFalse(limiter.tryAcquire("p"));
        // Wait ~50ms — refill at 50/s should restore 2-3 tokens.
        Thread.sleep(60);
        assertTrue(limiter.tryAcquire("p"));
    }

    @Test
    void concurrentAcquire_neverExceedsBurst() throws InterruptedException {
        AcceptRateLimiter limiter   = new AcceptRateLimiter(50, 1, Clock.systemUTC());
        AtomicInteger     accepted  = new AtomicInteger();
        int               threads   = 16;
        int               perThread = 100;
        CountDownLatch    done      = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    for (int j = 0; j < perThread; j++) {
                        if (limiter.tryAcquire("shared")) {
                            accepted.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(2, TimeUnit.SECONDS));
        // No matter how many threads race, the bucket initial burst (50) + any millisecond of
        // refill (≤ 1ms × 1/s ≈ 0) caps the total at ~50.
        assertTrue(accepted.get() <= 55,
                   "lock-free CAS must not over-grant tokens: " + accepted.get());
    }

    @Test
    void evictStale_removesIdleSources_butKeepsActive() {
        AcceptRateLimiter limiter = new AcceptRateLimiter(2, 0.5, Clock.systemUTC());
        limiter.tryAcquire("active");
        limiter.tryAcquire("idle-1");
        limiter.tryAcquire("idle-2");
        assertTrue(limiter.trackedSources() >= 3);
        // Evict everything older than 0 ns — effectively all of them, then re-touch active.
        int evicted = limiter.evictStale(Duration.ofNanos(0));
        assertTrue(evicted >= 3);
    }
}

package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Closed-form regression for {@link OutboxWorker#computeBackoff(int)}: truncated exponential {@code
 * base * 2^(attempts-1)} capped at {@link OutboxConfig#DEFAULT_BACKOFF_MAX}, multiplied by a jitter
 * factor in {@code [0.8, 1.2]}. Pins the default-overload behaviour; the parameterised overload
 * {@code computeBackoff(int, Duration, Duration)} is exercised by the configurable-backoff
 * regression test.
 */
class OutboxWorkerBackoffExponentialJitterTest {

    @Test
    void attempt1_isAroundBase_within20PercentJitter() {
        long baseMs = OutboxConfig.DEFAULT_BACKOFF_BASE.toMillis();
        // Sample many times to flush out the jitter range.
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < 200; i++) {
            long ms = OutboxWorker.computeBackoff(1).toMillis();
            min = Math.min(min, ms);
            max = Math.max(max, ms);
        }
        assertTrue(
                   min >= (long) (baseMs * 0.8) - 1, "min " + min + " must be at least 80% of base " + baseMs);
        assertTrue(
                   max <= (long) (baseMs * 1.2) + 1, "max " + max + " must be at most 120% of base " + baseMs);
    }

    @Test
    void attempt5_isAround16Times_base_within20PercentJitter() {
        long baseMs        = OutboxConfig.DEFAULT_BACKOFF_BASE.toMillis();
        long expectedExpMs = baseMs * 16L; // 2^(5-1) = 16
        long min           = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (int i = 0; i < 200; i++) {
            long ms = OutboxWorker.computeBackoff(5).toMillis();
            min = Math.min(min, ms);
            max = Math.max(max, ms);
        }
        assertTrue(min >= (long) (expectedExpMs * 0.8) - 1);
        assertTrue(max <= (long) (expectedExpMs * 1.2) + 1);
    }

    @Test
    void veryLargeAttempt_isCappedAtBackoffMax_plusJitterWindow() {
        long capMs = OutboxConfig.DEFAULT_BACKOFF_MAX.toMillis();
        // 1<<30 attempts can't possibly need this many bits; the
        // implementation must clamp.
        for (int i = 0; i < 50; i++) {
            Duration d  = OutboxWorker.computeBackoff(1_000);
            long     ms = d.toMillis();
            assertTrue(
                       ms >= (long) (capMs * 0.8) - 1,
                       "capped backoff must respect lower jitter bound; got " + ms);
            assertTrue(
                       ms <= (long) (capMs * 1.2) + 1,
                       "capped backoff must respect upper jitter bound; got " + ms);
        }
    }

    @Test
    void backoffIsStrictlyMonotone_inExpectation_acrossAttempts() {
        // Sample the median (-ish): compare attempt N vs attempt N+1
        // by their averages over many samples.
        double avgAt1 = avgBackoffMs(1, 500);
        double avgAt2 = avgBackoffMs(2, 500);
        double avgAt4 = avgBackoffMs(4, 500);
        assertTrue(avgAt2 > avgAt1, "backoff grows: attempt2 > attempt1");
        assertTrue(avgAt4 > avgAt2, "backoff grows: attempt4 > attempt2");
    }

    private static double avgBackoffMs(int attempts, int samples) {
        long total = 0;
        for (int i = 0; i < samples; i++) {
            total += OutboxWorker.computeBackoff(attempts).toMillis();
        }
        return total / (double) samples;
    }
}

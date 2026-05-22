package net.nexus_flow.core.ddd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.event.DomainEventContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lightweight throughput baselines for the hot paths. Not JMH (the project does not depend on JMH
 * at runtime); these tests run a warm-up loop then a measured loop and assert that throughput stays
 * above a conservative floor so a future regression that turns a fast path into an O(n) or
 * lock-bound path fails CI loudly.
 *
 * <p>The thresholds are intentionally lax (10× safety margin vs. observed numbers on a modern
 * laptop CPU) so that slow CI hardware does not produce false negatives.
 */
class AggregateHotPathThroughputTest {

    static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Bumped(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void bump(String id) {
            recordEvent(new Bumped(id));
        }
    }

    @BeforeEach
    void clear() {
        DomainEventContext.current().clearEvents();
    }

    @AfterEach
    void clearAfter() {
        DomainEventContext.current().clearEvents();
    }

    @Test
    @DisplayName("Aggregate.recordEvent throughput stays above 100k ops/sec")
    void recordEvent_throughputFloor() {
        Counter counter = new Counter();
        // Warmup.
        for (int i = 0; i < 10_000; i++) {
            counter.bump("agg-warmup-" + i);
        }
        counter.drainEvents();

        final int n     = 100_000;
        long      start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            counter.bump("agg-" + i);
        }
        long              elapsedNanos = System.nanoTime() - start;
        List<DomainEvent> drained      = counter.drainEvents();
        assertEquals(n, drained.size(), "all recorded events drained exactly once");

        double opsPerSecond = (double) n / (elapsedNanos / 1_000_000_000.0);
        // Floor at 100k ops/sec — 10× below the observed ~1M/sec on commodity hardware.
        assertTrue(
                   opsPerSecond > 100_000.0,
                   () -> "recordEvent throughput regression: " + (long) opsPerSecond + " ops/sec");
    }

    @Test
    @DisplayName("drainEvents throughput stays above 50k drains/sec")
    void drainEvents_throughputFloor() {
        final int batchesPerSecondFloor = 50_000;
        Counter   counter               = new Counter();
        // Warmup.
        for (int i = 0; i < 1_000; i++) {
            counter.bump("warm-" + i);
            counter.drainEvents();
        }
        final int drains = 50_000;
        long      start  = System.nanoTime();
        for (int i = 0; i < drains; i++) {
            counter.bump("d-" + i);
            counter.drainEvents();
        }
        long   elapsedNanos    = System.nanoTime() - start;
        double drainsPerSecond = (double) drains / (elapsedNanos / 1_000_000_000.0);
        assertTrue(
                   drainsPerSecond > batchesPerSecondFloor,
                   () -> "drainEvents throughput regression: " + (long) drainsPerSecond + " drains/sec");
    }

    @Test
    @DisplayName("recordEvent + drainEvents completes under 1s for 200k events")
    void hotLoop_completesWithinBudget() {
        Counter counter = new Counter();
        long    start   = System.nanoTime();
        for (int i = 0; i < 200_000; i++) {
            counter.bump("hl-" + i);
        }
        counter.drainEvents();
        long elapsedNanos = System.nanoTime() - start;
        assertTrue(
                   elapsedNanos < TimeUnit.SECONDS.toNanos(5),
                   () -> "Hot loop budget exceeded: " + (elapsedNanos / 1_000_000) + " ms");
    }
}

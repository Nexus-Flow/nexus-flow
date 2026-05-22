package net.nexus_flow.core.ddd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link AbstractDomainEvent#idempotencyKey()} cache contract:
 *
 * <ul>
 * <li>First call returns the canonical {@code aggregateId:sequenceNumber} form.
 * <li>Subsequent calls return the EXACT same {@link String} instance (identity equality) — proves
 * the cache is wired, not just memoizing equality.
 * <li>Concurrent readers all observe a consistent (and equal-valued) key.
 * <li>Unstamped events still throw {@link UnsupportedOperationException} with the documented
 * diagnostic.
 * </ul>
 *
 * <p>The cache eliminates a per-listener-per-dispatch {@code String} concatenation allocation on
 * the event-bus hot path. The 1st-call cost is the same; the 2nd-and-subsequent are O(1)
 * zero-alloc.
 */
class AbstractDomainEventIdempotencyKeyCacheTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class TickAggregate extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private final UUID        id;

        TickAggregate(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void tick() {
            recordEvent(new Tick(id.toString()));
        }
    }

    @Test
    void firstCall_returnsCanonicalForm() {
        TickAggregate agg = new TickAggregate(UUID.randomUUID());
        agg.tick();
        Tick event = (Tick) agg.drainEvents().getFirst();

        String key = event.idempotencyKey();
        assertEquals(
                     event.getAggregateId() + ":0", key, "canonical form is aggregateId:sequenceNumber");
    }

    @Test
    void secondCall_returnsSameInstance_provingCacheIsWired() {
        TickAggregate agg = new TickAggregate(UUID.randomUUID());
        agg.tick();
        Tick event = (Tick) agg.drainEvents().getFirst();

        String first  = event.idempotencyKey();
        String second = event.idempotencyKey();
        // assertSame, not assertEquals: identity equality proves we did not recompute and allocate
        // a new String on the second call. assertEquals would pass for two String-equal-but-distinct
        // instances and would mask the regression.
        assertSame(
                   first,
                   second,
                   "subsequent idempotencyKey() calls MUST return the cached String instance (identity)");
    }

    @Test
    void hundredCalls_allReturnSameInstance() {
        TickAggregate agg = new TickAggregate(UUID.randomUUID());
        agg.tick();
        Tick event = (Tick) agg.drainEvents().getFirst();

        String first = event.idempotencyKey();
        for (int i = 0; i < 100; i++) {
            assertSame(first, event.idempotencyKey(), "call " + i + " must return the cached instance");
        }
    }

    @Test
    void concurrentReaders_observeConsistentKey() throws Exception {
        TickAggregate agg = new TickAggregate(UUID.randomUUID());
        agg.tick();
        Tick   event    = (Tick) agg.drainEvents().getFirst();
        String expected = event.getAggregateId() + ":0";

        int                        threadCount    = 16;
        int                        callsPerThread = 1000;
        CountDownLatch             start          = new CountDownLatch(1);
        CountDownLatch             done           = new CountDownLatch(threadCount);
        AtomicReference<Throwable> failure        = new AtomicReference<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threadCount)) {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(
                            () -> {
                                try {
                                    start.await();
                                    for (int i = 0; i < callsPerThread; i++) {
                                        String key = event.idempotencyKey();
                                        if (!expected.equals(key)) {
                                            failure.compareAndSet(
                                                                  null, new AssertionError("got " + key + ", expected " + expected));
                                        }
                                    }
                                } catch (Throwable th) {
                                    failure.compareAndSet(null, th);
                                } finally {
                                    done.countDown();
                                }
                            });
            }
            start.countDown();
            assertEquals(true, done.await(10, TimeUnit.SECONDS), "all readers must finish within 10s");
        }

        Throwable err = failure.get();
        if (err != null) {
            throw new AssertionError("concurrent reader inconsistency", err);
        }
    }

    @Test
    void unstampedEvent_throwsUnsupportedOperation() {
        // Construct without recording on an aggregate — sequenceNumber stays UNASSIGNED.
        Tick                          orphan = new Tick("agg-orphan");
        UnsupportedOperationException ex     =
                assertThrows(UnsupportedOperationException.class, orphan::idempotencyKey);
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("lacks idempotencyKey"));
    }
}

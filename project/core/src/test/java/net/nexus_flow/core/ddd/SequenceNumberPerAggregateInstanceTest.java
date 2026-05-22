package net.nexus_flow.core.ddd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies the sequence-number counter for {@link AbstractDomainEvent#getSequenceNumber()} is per
 * aggregate instance, never per-runtime nor per-JVM. The aggregate now serialises accidental
 * concurrent recording so sequence assignment remains contiguous even when callers misuse a shared
 * instance.
 */
class SequenceNumberPerAggregateInstanceTest {

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
        private final UUID        id               = UUID.randomUUID();

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void bump() {
            recordEvent(new Bumped(id.toString()));
        }
    }

    @Test
    void distinct_aggregates_haveIndependent_counters() {
        Counter a = new Counter();
        Counter b = new Counter();
        a.bump();
        a.bump();
        a.bump();
        b.bump();
        b.bump();

        List<DomainEvent> drainedA = a.drainEvents();
        List<DomainEvent> drainedB = b.drainEvents();

        assertEquals(
                     List.of(0L, 1L, 2L),
                     sequencesOf(drainedA),
                     "aggregate A must produce a 0-based, monotonic sequence");
        assertEquals(
                     List.of(0L, 1L),
                     sequencesOf(drainedB),
                     "aggregate B must restart its counter at 0 — counters are NOT shared");
        // Cross-aggregate: different instances have different keys even for same sequence
        assertNotEquals(drainedA.getFirst().idempotencyKey(), drainedB.getFirst().idempotencyKey());
    }

    @Test
    void shared_aggregate_across_threads_serialisesRecording() throws InterruptedException {
        Counter        shared          = new Counter();
        int            threads         = 4;
        int            eventsPerThread = 50;
        CountDownLatch ready           = new CountDownLatch(threads);
        CountDownLatch go              = new CountDownLatch(1);
        CountDownLatch done            = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(
                    () -> {
                        ready.countDown();
                        try {
                            if (!go.await(2, TimeUnit.SECONDS)) {
                                fail("test latch timeout");
                            }
                            for (int j = 0; j < eventsPerThread; j++) {
                                shared.bump();
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    },
                    "share-misuse-" + i)
                    .start();
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        List<DomainEvent> drained = shared.drainEvents();
        assertEquals(
                     threads * eventsPerThread,
                     drained.size(),
                     "all concurrently recorded events must be retained");
        assertEquals(
                     java.util.stream.LongStream.range(0, threads * eventsPerThread).boxed().toList(),
                     sequencesOf(drained),
                     "sequence numbers must remain contiguous even under accidental concurrent recording");
    }

    private static List<Long> sequencesOf(List<DomainEvent> events) {
        return events.stream().map(e -> ((AbstractDomainEvent) e).getSequenceNumber()).toList();
    }
}

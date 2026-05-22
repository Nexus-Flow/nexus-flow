package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Verifies two concurrent appends with the same expectedVersion on the same stream yield exactly
 * one Success and one VersionConflict.
 */
class EventStoreRejectsConcurrentAppendsTest {

    static final class Ping extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Ping(String aggId) {
            super(aggId);
        }
    }

    @Test
    void twoThreads_appendAtExpectedVersionZero_yieldSuccessAndConflict() throws Exception {
        UUID     aggId  = UUID.randomUUID();
        StreamId stream = new StreamId("test.Aggregate", aggId);

        int           iterations    = 100;
        AtomicInteger successCount  = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        for (int i = 0; i < iterations; i++) {
            EventStore     freshStore = new InMemoryEventStore();
            CountDownLatch ready      = new CountDownLatch(2);
            CountDownLatch go         = new CountDownLatch(1);
            try (ExecutorService es = Executors.newFixedThreadPool(2)) {
                Future<AppendResult> f1 =
                        es.submit(
                                  () -> {
                                      ready.countDown();
                                      go.await();
                                      return freshStore.append(stream, 0L, List.of(new Ping(aggId.toString())));
                                  });
                Future<AppendResult> f2 =
                        es.submit(
                                  () -> {
                                      ready.countDown();
                                      go.await();
                                      return freshStore.append(stream, 0L, List.of(new Ping(aggId.toString())));
                                  });
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                go.countDown();
                AppendResult r1 = f1.get(2, TimeUnit.SECONDS);
                AppendResult r2 = f2.get(2, TimeUnit.SECONDS);

                int s = 0, c = 0;
                for (AppendResult r : List.of(r1, r2)) {
                    if (r instanceof AppendResult.Success)
                        s++;
                    else if (r instanceof AppendResult.VersionConflict)
                        c++;
                }
                assertEquals(1, s, "exactly one Success expected (iter " + i + ")");
                assertEquals(1, c, "exactly one VersionConflict expected (iter " + i + ")");
                successCount.addAndGet(s);
                conflictCount.addAndGet(c);

                // The conflict carries expected=0 and actual=1.
                AppendResult                 conflict = r1 instanceof AppendResult.VersionConflict ? r1 : r2;
                AppendResult.VersionConflict vc       =
                        assertInstanceOf(AppendResult.VersionConflict.class, conflict);
                assertEquals(0L, vc.expectedVersion());
                assertEquals(1L, vc.actualVersion());
            }
        }
        assertEquals(iterations, successCount.get());
        assertEquals(iterations, conflictCount.get());
    }
}

package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * commit (c) — concurrent {@link OutboxStorage#claimBatch(int, java.time.Instant)} calls must never
 * return the same {@link OutboxId} twice and must never lose eligible rows.
 */
class ClaimBatchIsConcurrentSafeTest {

    @Test
    void fourThreadsClaimingDisjointBatches_coverAllPendingRowsWithoutDuplicates() throws Exception {
        Instant               now     = Instant.parse("2026-05-19T13:00:00Z");
        Clock                 clock   = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        int total = 100;
        for (int i = 0; i < total; i++) {
            storage.append(OutboxFixtures.pending("agg-" + i, 0, now.minusMillis(total - i)));
        }
        assertEquals(total, storage.size());

        int                        threads  = 4;
        int                        perBatch = 25;
        CountDownLatch             ready    = new CountDownLatch(threads);
        CountDownLatch             go       = new CountDownLatch(1);
        AtomicReference<Throwable> failure  = new AtomicReference<>();

        List<Future<List<OutboxRecord>>> futures = new ArrayList<>(threads);

        try (ExecutorService exec = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                futures.add(
                            exec.submit(
                                        () -> {
                                            try {
                                                ready.countDown();
                                                go.await();
                                                return storage.claimBatch(perBatch, now);
                                            } catch (Throwable ex) {
                                                failure.set(ex);
                                                return List.of();
                                            }
                                        }));
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();

            Set<OutboxId> seen         = new HashSet<>();
            int           totalClaimed = 0;
            for (Future<List<OutboxRecord>> future : futures) {
                List<OutboxRecord> batch = future.get(5, TimeUnit.SECONDS);
                for (OutboxRecord outboxRecord : batch) {
                    assertTrue(
                               seen.add(outboxRecord.outboxId()),
                               "duplicate outboxId across batches: " + outboxRecord.outboxId());
                    totalClaimed++;
                }
            }

            if (failure.get() != null) {
                throw new AssertionError("claim worker failed", failure.get());
            }

            assertEquals(
                         total, totalClaimed, "union of batches must cover every PENDING row exactly once");

            // No record may still be PENDING.
            long stillPending =
                    storage.snapshot().stream()
                            .filter(outboxRecord -> outboxRecord.status() == OutboxStatus.PENDING)
                            .count();
            assertEquals(0, stillPending, "every row must have been flipped to IN_FLIGHT");
        }
    }
}

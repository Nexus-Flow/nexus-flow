package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the invariant that {@link InMemoryOutboxStorage#claimBatch(int, Instant)} never overwrites a
 * {@linkplain OutboxStatus#PUBLISHED terminal} state (or any non-PENDING state) back to {@link
 * OutboxStatus#IN_FLIGHT} under concurrent {@code claimBatch} ↔ {@code markPublished} operations.
 *
 * <p>The race window historically: {@code claimBatch} held {@code claimLock} while iterating {@code
 * byId.values().stream()} — a weakly-consistent view — and then unconditionally called {@code
 * byId.put(id, IN_FLIGHT)} for every row whose snapshot status was PENDING. A concurrent {@code
 * markPublished(id)} (which uses {@code byId.compute} per-key, NOT serialised against {@code
 * claimLock}) could transition the row to PUBLISHED between the snapshot read and the put, after
 * which {@code claimBatch} silently reverted it to IN_FLIGHT.
 *
 * <p>{@code InMemoryOutboxStorage}'s {@code requireResolvableFromInFlight} guard permits {@code
 * PENDING} as a source state (legacy "permissive" contract), so {@code markPublished} can
 * legitimately be called on a PENDING row — that is the vector that opens the race window.
 *
 * <p>The current invariant: every row that the marker successfully transitioned to PUBLISHED MUST
 * remain in PUBLISHED in the final storage snapshot. Pre-fix this assertion would fail with
 * non-zero probability under the stress pattern below. With the current implementation it passes
 * every time because {@code claimBatch} re-checks {@code current.status() == PENDING} under per-key
 * atomicity via {@code byId.computeIfPresent}.
 */
class InMemoryOutboxStorageClaimRaceWithMarkPublishedTest {

    @Test
    void terminalRowsAreNeverRevertedToInFlightByConcurrentClaim() throws Exception {
        Instant               now     = Instant.parse("2026-05-23T13:00:00Z");
        Clock                 clock   = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        int            rowCount = 200;
        List<OutboxId> ids      = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            OutboxRecord row = OutboxFixtures.pending("agg-" + i, 0, now.minusMillis(rowCount - i));
            storage.append(row);
            ids.add(row.outboxId());
        }
        assertEquals(rowCount, storage.size());

        // Rows the marker successfully transitioned to PUBLISHED. Concurrent-safe so
        // four marker threads can add without contention.
        Set<OutboxId> markedPublished = ConcurrentHashMap.newKeySet();

        CountDownLatch             go             = new CountDownLatch(1);
        AtomicReference<Throwable> failure        = new AtomicReference<>();
        int                        markerThreads  = 4;
        int                        claimerThreads = 4;

        try (ExecutorService pool = Executors.newFixedThreadPool(markerThreads + claimerThreads)) {

            // Marker tasks: each thread fires markPublished on a disjoint slice of the ids
            // (i % markerThreads == sliceIndex). Each id is marked at most once.
            for (int t = 0; t < markerThreads; t++) {
                final int slice = t;
                pool.submit(
                            () -> {
                                try {
                                    go.await();
                                    for (int i = slice; i < rowCount; i += markerThreads) {
                                        OutboxId id = ids.get(i);
                                        try {
                                            storage.markPublished(id);
                                            markedPublished.add(id);
                                        } catch (IllegalOutboxTransitionException ignored) {
                                            // Defensive: cannot happen with the current contract because each id is
                                            // marked exactly once and the only forbidden source states are
                                            // PUBLISHED / FAILED_TERMINAL, neither of which any other writer in this
                                            // test produces. If the contract tightens later (PENDING forbidden),
                                            // we'd see this; the assertion below still pins the invariant.
                                        }
                                    }
                                } catch (Throwable ex) {
                                    failure.compareAndSet(null, ex);
                                }
                            });
            }

            // Claimer tasks: aggressively claim batches to maximize the race window. The race
            // fires when claimBatch's weakly-consistent stream snapshot has already captured a
            // row as PENDING but the marker's byId.compute lands before claimBatch's per-key
            // write. 50 passes × 4 threads × 20 rows/batch saturates the window.
            for (int t = 0; t < claimerThreads; t++) {
                pool.submit(
                            () -> {
                                try {
                                    go.await();
                                    for (int pass = 0; pass < 50; pass++) {
                                        List<OutboxRecord> batch = storage.claimBatch(20, now);
                                        if (batch.isEmpty()) {
                                            break;
                                        }
                                    }
                                } catch (Throwable ex) {
                                    failure.compareAndSet(null, ex);
                                }
                            });
            }

            go.countDown();
            pool.shutdown();
            assertTrue(
                       pool.awaitTermination(15, TimeUnit.SECONDS),
                       "claim/mark worker threads did not finish in time");
        }

        if (failure.get() != null) {
            throw new AssertionError("worker failed", failure.get());
        }

        // Sharp invariant: every row the marker successfully transitioned to PUBLISHED MUST
        // remain in PUBLISHED state. A row reverting to IN_FLIGHT (or any other state) means
        // claimBatch's post-snapshot write overwrote the marker's transition.
        List<String> reverted = new ArrayList<>();
        for (OutboxId id : markedPublished) {
            OutboxRecord row = storage.findById(id);
            assertNotNull(row, "marker-published row vanished: " + id);
            if (row.status() != OutboxStatus.PUBLISHED) {
                reverted.add(id + " ended in " + row.status() + " (was markPublished'd)");
            }
        }
        if (!reverted.isEmpty()) {
            fail(
                 "claimBatch overwrote a terminal PUBLISHED state under concurrent mark/claim:\n  "
                         + String.join("\n  ", reverted));
        }

        // Sanity: at least some rows should have been markPublished'd. Without this, the
        // invariant assertion above would vacuously pass.
        assertTrue(
                   !markedPublished.isEmpty(),
                   "marker thread did not succeed on any row — test scenario is broken");
    }
}

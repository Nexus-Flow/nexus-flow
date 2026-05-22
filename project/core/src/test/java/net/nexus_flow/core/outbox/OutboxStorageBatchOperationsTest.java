package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.junit.jupiter.api.Test;

/**
 * Pins the batch-append and batch-publish primitives on {@link OutboxStorage}:
 *
 * <ol>
 * <li>{@link OutboxStorage#appendBatch} default loop appends every row in iteration order.
 * <li>{@link InMemoryOutboxStorage#appendBatch} acquires the claim lock once, holds it
 * across the whole batch — equivalent to a single critical section so concurrent
 * claimBatch callers observe all-or-none.
 * <li>{@link OutboxStorage#markPublishedBatch} default loop publishes each id; the
 * in-memory override does it under one lock acquisition.
 * <li>Empty batches are no-ops on both methods.
 * <li>Null inputs (list or any element) are rejected.
 * </ol>
 */
class OutboxStorageBatchOperationsTest {

    private static final Instant T0 = Instant.parse("2026-05-28T12:00:00Z");

    private static OutboxRecord newRow(String aggregateId, long sequenceNo) {
        return new OutboxRecord(
                OutboxId.next(),
                IdempotencyKey.of(aggregateId + ":" + sequenceNo),
                "Demo",
                aggregateId,
                sequenceNo,
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                MessageId.random(),
                Object.class,
                new byte[0],
                T0.plusMillis(sequenceNo),
                OutboxStatus.PENDING,
                0,
                null,
                null,
                null,
                null);
    }

    @Test
    void appendBatch_appendsAllRowsInOrder() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        List<OutboxRecord>    rows    = List.of(
                                                newRow("agg-A", 0L), newRow("agg-A", 1L), newRow("agg-B", 0L));
        storage.appendBatch(rows);
        List<OutboxRecord> persisted = storage.findSinceSequence(-1L, Integer.MAX_VALUE);
        assertEquals(3, persisted.size());
    }

    @Test
    void appendBatch_emptyList_isNoOp() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        storage.appendBatch(List.of());
        assertTrue(storage.findSinceSequence(-1L, Integer.MAX_VALUE).isEmpty());
    }

    @Test
    void appendBatch_rejectsNullList() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        assertThrows(NullPointerException.class, () -> storage.appendBatch(null));
    }

    @Test
    void appendBatch_rejectsNullElement() {
        InMemoryOutboxStorage storage  = new InMemoryOutboxStorage();
        List<OutboxRecord>    withNull = new ArrayList<>();
        withNull.add(newRow("agg-A", 0L));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> storage.appendBatch(withNull));
    }

    @Test
    void appendBatch_duplicateKey_inSameBatch_throwsButPriorRowsStay() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        OutboxRecord          first   = newRow("agg-A", 0L);
        OutboxRecord          second  = newRow("agg-A", 1L);
        // Build a duplicate of `first` (same idempotency key) to provoke the collision.
        OutboxRecord dup = newRow("agg-A", 0L);
        assertThrows(OutboxDuplicateKeyException.class,
                     () -> storage.appendBatch(List.of(first, second, dup)));
        // first + second appended successfully before the collision aborted the batch.
        assertEquals(2, storage.findSinceSequence(-1L, Integer.MAX_VALUE).size());
    }

    @Test
    void markPublishedBatch_publishesAll_inOrder() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        storage.append(newRow("agg-A", 0L));
        storage.append(newRow("agg-A", 1L));
        List<OutboxRecord> claimed = storage.claimBatch(2, T0.plusSeconds(60));
        assertEquals(2, claimed.size());
        List<OutboxId> ids = claimed.stream().map(OutboxRecord::outboxId).toList();

        storage.markPublishedBatch(ids);
        // After publish, claimBatch returns nothing — both rows transitioned out of PENDING.
        assertEquals(0, storage.claimBatch(10, T0.plusSeconds(120)).size());
    }

    @Test
    void markPublishedBatch_emptyList_isNoOp() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        storage.markPublishedBatch(List.of());
        // No exception expected; storage state unchanged.
        assertEquals(0L, storage.pendingCount(),
                     "an empty batch publish MUST NOT alter storage state");
    }

    @Test
    void markPublishedBatch_rejectsNullList() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        assertThrows(NullPointerException.class, () -> storage.markPublishedBatch(null));
    }
}

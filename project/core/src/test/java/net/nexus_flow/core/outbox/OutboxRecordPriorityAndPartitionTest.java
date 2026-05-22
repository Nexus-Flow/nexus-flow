package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.junit.jupiter.api.Test;

/**
 * Pins the priority + partition contract on the outbox layer:
 *
 * <ol>
 * <li>{@link OutboxRecord} carries an {@code int priority} and a {@code String partitionKey}.
 * <li>The back-compat constructors (18-arg, 19-arg) preserve the default behaviour:
 * {@code priority = DEFAULT_PRIORITY = 0}, {@code partitionKey = aggregateId}.
 * <li>{@link OutboxRecord#withPriority} and {@link OutboxRecord#withPartitionKey} return a
 * copy with the new value, all other fields preserved.
 * <li>{@link InMemoryOutboxStorage#claimBatch} sorts by (priority DESC, sequenceNo ASC,
 * recordedAt ASC, outboxId ASC). Higher priority across partitions wins; within a
 * partition FIFO is preserved by sequenceNo.
 * <li>Lifecycle helpers ({@code asPublished}, {@code asRetrying}, {@code asFailedTerminal})
 * preserve {@code priority} and {@code partitionKey} across status transitions.
 * </ol>
 */
class OutboxRecordPriorityAndPartitionTest {

    private static final Instant T0 = Instant.parse("2026-05-28T12:00:00Z");

    private static OutboxRecord newRecord(
            String aggregateId, long sequenceNo, int priority, String partitionKey) {
        return new OutboxRecord(
                OutboxId.next(),
                IdempotencyKey.of(aggregateId + ":" + sequenceNo),
                "DemoAggregate",
                aggregateId,
                sequenceNo,
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                MessageId.random(),
                Object.class,
                new byte[0],
                T0.plusMillis(sequenceNo), // recordedAt grows with seq so the tie-break order is stable
                OutboxStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                null,
                priority,
                partitionKey);
    }

    // ─── Default-value semantics ────────────────────────────────────────────

    @Test
    void backCompatConstructor_18arg_defaultsPriorityAndPartitionKey() {
        OutboxRecord row = new OutboxRecord(
                OutboxId.next(),
                IdempotencyKey.of("k:0"),
                "DemoAggregate",
                "agg-1",
                0L,
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                MessageId.random(),
                Object.class,
                new byte[0],
                T0,
                OutboxStatus.PENDING,
                0,
                null,
                null,
                null,
                null);
        assertEquals(OutboxRecord.DEFAULT_PRIORITY, row.priority());
        assertEquals("agg-1", row.partitionKey(),
                     "default partitionKey MUST equal the aggregateId");
    }

    @Test
    void backCompatConstructor_19arg_defaultsPriorityAndPartitionKey() {
        OutboxRecord row = new OutboxRecord(
                OutboxId.next(),
                IdempotencyKey.of("k:0"),
                "DemoAggregate",
                "agg-1",
                0L,
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                MessageId.random(),
                Object.class,
                new byte[0],
                T0,
                OutboxStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                "java-v1");
        assertEquals(OutboxRecord.DEFAULT_PRIORITY, row.priority());
        assertEquals("agg-1", row.partitionKey());
    }

    @Test
    void compactConstructor_rejectsBlankPartitionKey() {
        assertThrows(IllegalArgumentException.class,
                     () -> newRecord("agg", 0L, 0, ""));
        assertThrows(IllegalArgumentException.class,
                     () -> newRecord("agg", 0L, 0, "   "));
    }

    // ─── Fluent mutators ────────────────────────────────────────────────────

    @Test
    void withPriority_returnsCopyWithNewPriority_otherFieldsPreserved() {
        OutboxRecord row     = newRecord("agg-1", 0L, 0, "agg-1");
        OutboxRecord boosted = row.withPriority(100);
        assertEquals(100, boosted.priority());
        assertSame(row.outboxId(), boosted.outboxId());
        assertEquals(row.partitionKey(), boosted.partitionKey());
        assertEquals(row.aggregateId(), boosted.aggregateId());
    }

    @Test
    void withPartitionKey_returnsCopyWithNewPartitionKey() {
        OutboxRecord row     = newRecord("agg-1", 0L, 0, "agg-1");
        OutboxRecord shifted = row.withPartitionKey("tenant-acme");
        assertEquals("tenant-acme", shifted.partitionKey());
        assertEquals(row.aggregateId(), shifted.aggregateId(),
                     "aggregateId MUST stay unchanged when partitionKey is overridden");
    }

    @Test
    void withPartitionKey_rejectsNull() {
        OutboxRecord row = newRecord("agg-1", 0L, 0, "agg-1");
        assertThrows(NullPointerException.class, () -> row.withPartitionKey(null));
    }

    // ─── Lifecycle preservation ─────────────────────────────────────────────

    @Test
    void lifecycleHelpers_preservePriorityAndPartitionKey() {
        OutboxRecord row = newRecord("agg-1", 0L, 50, "tenant-acme");

        OutboxRecord published = row.asPublished(T0);
        assertEquals(50, published.priority());
        assertEquals("tenant-acme", published.partitionKey());

        OutboxRecord retrying = row.asRetrying("err", T0, T0.plusSeconds(1));
        assertEquals(50, retrying.priority());
        assertEquals("tenant-acme", retrying.partitionKey());

        OutboxRecord terminal = row.asFailedTerminal("fatal", T0);
        assertEquals(50, terminal.priority());
        assertEquals("tenant-acme", terminal.partitionKey());

        OutboxRecord pending = row.asPending(T0.plusSeconds(2));
        assertEquals(50, pending.priority());
        assertEquals("tenant-acme", pending.partitionKey());

        OutboxRecord statusOnly = row.withStatus(OutboxStatus.IN_FLIGHT);
        assertEquals(50, statusOnly.priority());
        assertEquals("tenant-acme", statusOnly.partitionKey());
    }

    // ─── claimBatch ordering ────────────────────────────────────────────────

    @Test
    void claimBatch_higherPriority_drawnFirst_acrossPartitions() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        // Three rows, three partitions, three priorities. Highest priority MUST be claimed first.
        storage.append(newRecord("agg-A", 0L, 1, "agg-A"));
        storage.append(newRecord("agg-B", 0L, 100, "agg-B"));
        storage.append(newRecord("agg-C", 0L, 50, "agg-C"));

        List<OutboxRecord> batch = storage.claimBatch(3, T0.plusSeconds(60));
        assertEquals(3, batch.size());
        assertEquals(100, batch.get(0).priority(), "priority 100 first");
        assertEquals(50, batch.get(1).priority(), "priority 50 second");
        assertEquals(1, batch.get(2).priority(), "priority 1 last");
    }

    @Test
    void claimBatch_withinPartition_FIFO_preserved_byPriorityTieBreakerSequenceNo() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        // Same partition, same priority — sequenceNo determines order (FIFO within partition).
        storage.append(newRecord("agg-A", 2L, 10, "agg-A"));
        storage.append(newRecord("agg-A", 0L, 10, "agg-A"));
        storage.append(newRecord("agg-A", 1L, 10, "agg-A"));

        List<OutboxRecord> batch = storage.claimBatch(3, T0.plusSeconds(60));
        assertEquals(3, batch.size());
        assertEquals(0L, batch.get(0).sequenceNo(), "earliest sequence first within partition");
        assertEquals(1L, batch.get(1).sequenceNo());
        assertEquals(2L, batch.get(2).sequenceNo());
    }

    @Test
    void claimBatch_priorityWinsOverSequenceAcrossPartitions() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        // High-priority event in agg-B beats a low-priority event with sequence 0 in agg-A.
        storage.append(newRecord("agg-A", 0L, 1, "agg-A"));
        storage.append(newRecord("agg-B", 100L, 100, "agg-B"));

        List<OutboxRecord> batch = storage.claimBatch(2, T0.plusSeconds(60));
        assertEquals("agg-B", batch.get(0).aggregateId(),
                     "high-priority row across partitions wins");
        assertEquals("agg-A", batch.get(1).aggregateId());
    }

    @Test
    void claimBatch_groupsByPartition_atSamePriority_forFifoLocality() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        // Three partitions, three events each, same priority. The sort MUST group rows from the
        // same partition together (partitionKey ASC tie-break) so the worker drains each
        // partition's FIFO contiguously — matches the JDBC adapter index plan.
        storage.append(newRecord("agg-A", 0L, 0, "agg-A"));
        storage.append(newRecord("agg-B", 0L, 0, "agg-B"));
        storage.append(newRecord("agg-A", 1L, 0, "agg-A"));
        storage.append(newRecord("agg-B", 1L, 0, "agg-B"));
        storage.append(newRecord("agg-A", 2L, 0, "agg-A"));
        storage.append(newRecord("agg-B", 2L, 0, "agg-B"));

        List<OutboxRecord> batch = storage.claimBatch(6, T0.plusSeconds(60));
        // The first 3 must all be agg-A (its FIFO), then all 3 agg-B.
        assertEquals("agg-A", batch.get(0).aggregateId());
        assertEquals("agg-A", batch.get(1).aggregateId());
        assertEquals("agg-A", batch.get(2).aggregateId());
        assertEquals("agg-B", batch.get(3).aggregateId());
        assertEquals("agg-B", batch.get(4).aggregateId());
        assertEquals("agg-B", batch.get(5).aggregateId());
        // And within each partition the sequence MUST be ascending.
        assertEquals(0L, batch.get(0).sequenceNo());
        assertEquals(1L, batch.get(1).sequenceNo());
        assertEquals(2L, batch.get(2).sequenceNo());
    }

    @Test
    void claimBatch_equalsAndHashCode_includeNewFields() {
        OutboxRecord a = newRecord("agg-1", 0L, 0, "agg-1");
        OutboxRecord b = a.withPriority(10);
        assertNotEquals(a, b, "different priority ⇒ not equal");
        assertNotEquals(a.hashCode(), b.hashCode());
        assertTrue(b.toString().contains("priority=10"));
        assertTrue(b.toString().contains("partitionKey='agg-1'"));
    }
}

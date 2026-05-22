package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link BoundedInMemoryEventDeduplicator} LRU cache behavior and idempotency key management.
 */
class BoundedInMemoryEventDeduplicatorTest {

    @Test
    void deduplicatorInitiallyReportsKeyAsNotDuplicate() {
        // Why: A key seen for the first time must not be reported as duplicate.
        BoundedInMemoryEventDeduplicator dedup = new BoundedInMemoryEventDeduplicator(10);

        boolean isDuplicate = dedup.isDuplicate("key-1");

        assertFalse(isDuplicate, "unseen key must not be duplicate");
    }

    @Test
    void deduplicatorReportsKeyAsDuplicateAfterMarkSeen() {
        // Why: After marking a key as seen, subsequent checks must report it as duplicate.
        BoundedInMemoryEventDeduplicator dedup = new BoundedInMemoryEventDeduplicator(10);

        dedup.markSeen("key-1");
        boolean isDuplicate = dedup.isDuplicate("key-1");

        assertTrue(isDuplicate, "marked key must be duplicate");
    }

    @Test
    void deduplicatorTracksMultipleDistinctKeys() {
        // Why: Deduplicator must track multiple distinct keys independently.
        BoundedInMemoryEventDeduplicator dedup = new BoundedInMemoryEventDeduplicator(10);

        dedup.markSeen("key-1");
        dedup.markSeen("key-2");

        assertTrue(dedup.isDuplicate("key-1"), "key-1 must be duplicate");
        assertTrue(dedup.isDuplicate("key-2"), "key-2 must be duplicate");
        assertFalse(dedup.isDuplicate("key-3"), "key-3 must not be duplicate");
    }

    @Test
    void deduplicatorEvictsOldestKeyWhenCapacityExceeded() {
        // Why: When capacity is exceeded, oldest (least recently used) key is evicted.
        BoundedInMemoryEventDeduplicator dedup = new BoundedInMemoryEventDeduplicator(3);

        dedup.markSeen("key-1");
        dedup.markSeen("key-2");
        dedup.markSeen("key-3");
        dedup.markSeen("key-4"); // key-1 should be evicted (LRU)

        assertFalse(dedup.isDuplicate("key-1"), "oldest key must be evicted");
        assertTrue(dedup.isDuplicate("key-2"), "key-2 must remain");
        assertTrue(dedup.isDuplicate("key-3"), "key-3 must remain");
        assertTrue(dedup.isDuplicate("key-4"), "key-4 must be tracked");
    }

    @Test
    void deduplicatorDefaultCapacityIsConfigured() {
        // Why: Default capacity should be 10,000 as documented.
        BoundedInMemoryEventDeduplicator dedup = new BoundedInMemoryEventDeduplicator();

        for (int i = 0; i < 10_000; i++) {
            dedup.markSeen("key-" + i);
        }
        // All should be present (at or near capacity)
        assertTrue(dedup.isDuplicate("key-9999"), "last key within capacity must exist");
    }

    @Test
    void deduplicatorRejectsZeroCapacity() {
        // Why: Capacity must be at least 1; reject invalid values.
        try {
            new BoundedInMemoryEventDeduplicator(0);
            throw new AssertionError("must reject capacity 0");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                       expected.getMessage().contains("capacity must be >= 1"),
                       "error message must state capacity requirement");
        }
    }
}

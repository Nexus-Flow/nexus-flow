package net.nexus_flow.core.cqrs.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LRU-bounded, thread-safe in-memory {@link EventDeduplicator}.
 *
 * <p>Keeps track of the most recent {@code capacity} idempotency keys. When the bound is reached
 * the oldest entry is evicted (LRU). Suitable for in-process deduplication of Outbox replays and
 * saga restarts where the event window fits in memory.
 *
 * <p>Default capacity: 10 000 keys (≈ 400 KB with 40-char keys).
 */
public final class BoundedInMemoryEventDeduplicator implements EventDeduplicator {

    private enum EntryState {
        IN_FLIGHT,
        PROCESSED
    }

    public static final int DEFAULT_CAPACITY = 10_000;

    private final Map<String, EntryState> seen;

    /** Creates a deduplicator with {@link #DEFAULT_CAPACITY}. */
    public BoundedInMemoryEventDeduplicator() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a deduplicator with the requested LRU capacity.
     *
     * @param capacity the maximum number of idempotency keys to keep in memory
     * @throws IllegalArgumentException if {@code capacity < 1}
     */
    public BoundedInMemoryEventDeduplicator(int capacity) {
        if (capacity < 1)
            throw new IllegalArgumentException("capacity must be >= 1, got: " + capacity);
        this.seen =
                new LinkedHashMap<>(capacity, 0.75f, /* accessOrder= */ true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, EntryState> eldest) {
                        return size() > capacity;
                    }
                };
    }

    /**
     * Returns whether the key is already being processed or has completed successfully.
     *
     * @param idempotencyKey the key to check
     * @return {@code true} if the key is already present in the deduplication window
     */
    @Override
    public synchronized boolean isDuplicate(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return seen.get(idempotencyKey) != null;
    }

    /**
     * Marks the key as successfully processed.
     *
     * @param idempotencyKey the key that completed successfully
     */
    @Override
    public synchronized void markSeen(String idempotencyKey) {
        seen.put(Objects.requireNonNull(idempotencyKey, "idempotencyKey"), EntryState.PROCESSED);
    }

    synchronized boolean tryStartProcessing(String idempotencyKey) {
        String key = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (seen.get(key) != null) {
            return false;
        }
        seen.put(key, EntryState.IN_FLIGHT);
        return true;
    }

    synchronized void releaseProcessing(String idempotencyKey) {
        String key = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (seen.get(key) == EntryState.IN_FLIGHT) {
            seen.remove(key);
        }
    }
}

package net.nexus_flow.core.cqrs.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, thread-safe in-memory {@link DeadLetterQueue}.
 *
 * <p>When the capacity is reached the <em>oldest</em> entry is silently evicted to make room for
 * the new one (ring-buffer semantics). A {@link System.Logger} warning is emitted on eviction so
 * operators know the DLQ is overflowing.
 */
public final class InMemoryDeadLetterQueue implements DeadLetterQueue {

    private static final System.Logger LOG =
            System.getLogger(InMemoryDeadLetterQueue.class.getName());

    /** Default capacity: last 1 000 dead letters are kept in memory. */
    public static final int DEFAULT_CAPACITY = 1_000;

    /**
     * Upper bound on the {@link ArrayDeque} initial-capacity hint. Prevents pre-allocating a huge
     * backing array when {@code capacity} is set to a pathologically large value (e.g. for a
     * grow-as-needed bound that legitimately tolerates millions of entries but rarely fills). The
     * deque grows beyond this hint on demand; this only affects the start-up allocation size.
     */
    public static final int INITIAL_CAPACITY_HINT_CAP = 1024;

    private final int                                      capacity;
    private final ArrayDeque<DeadLetterEntry>              queue;
    /**
     * Mutex guarding {@link #queue}. {@link java.util.concurrent.locks.ReentrantLock} (not
     * intrinsic monitor) matches the codebase-wide convention for contention-sensitive
     * mutexes: AQS-based parking is 2.7× faster than HotSpot heavyweight monitor inflation
     * under contention (JDK 21+, validated by {@code LockPrimitiveBenchmark}). Uncontended
     * acquire cost is parity with {@code synchronized}.
     */
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    /** Creates an in-memory dead-letter queue with {@link #DEFAULT_CAPACITY}. */
    public InMemoryDeadLetterQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates an in-memory dead-letter queue with the requested capacity.
     *
     * @param capacity the maximum number of entries to retain in memory
     * @throws IllegalArgumentException if {@code capacity < 1}
     */
    public InMemoryDeadLetterQueue(int capacity) {
        if (capacity < 1)
            throw new IllegalArgumentException("capacity must be >= 1, got: " + capacity);
        this.capacity = capacity;
        this.queue    = new ArrayDeque<>(Math.min(capacity, INITIAL_CAPACITY_HINT_CAP));
    }

    /** {@inheritDoc} */
    @Override
    public void enqueue(DeadLetterEntry entry) {
        java.util.Objects.requireNonNull(entry, "entry");
        DeadLetterEntry evicted;
        lock.lock();
        try {
            evicted = queue.size() >= capacity ? queue.poll() : null;
            queue.add(entry);
        } finally {
            lock.unlock();
        }
        // Logging outside the lock — keeps the critical section minimal. Both log calls go
        // through Supplier-based gating so they cost zero when WARNING is not loggable.
        if (evicted != null) {
            final DeadLetterEntry evictedFinal = evicted;
            LOG.log(
                    System.Logger.Level.WARNING,
                    () -> "[DLQ] Capacity "
                            + capacity
                            + " reached — evicting oldest entry: "
                            + evictedFinal.event().getClass().getSimpleName()
                            + ". New entry: "
                            + entry.event().getClass().getSimpleName());
        }
        LOG.log(
                System.Logger.Level.WARNING,
                () -> "[DLQ] Dead letter enqueued — event="
                        + entry.event().getClass().getSimpleName()
                        + " listener="
                        + entry.listenerClass().getSimpleName()
                        + " attempts="
                        + entry.totalAttempts()
                        + " cause="
                        + entry.cause().getMessage());
    }

    /** {@inheritDoc} */
    @Override
    public List<DeadLetterEntry> drain() {
        lock.lock();
        try {
            List<DeadLetterEntry> result = new ArrayList<>(queue);
            queue.clear();
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }
}

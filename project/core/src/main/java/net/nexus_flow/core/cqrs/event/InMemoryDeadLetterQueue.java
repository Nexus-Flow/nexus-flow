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

    private final int                         capacity;
    private final ArrayDeque<DeadLetterEntry> queue;

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
    public synchronized void enqueue(DeadLetterEntry entry) {
        java.util.Objects.requireNonNull(entry, "entry");
        if (queue.size() >= capacity) {
            DeadLetterEntry evicted = queue.poll();
            LOG.log(
                    System.Logger.Level.WARNING,
                    () -> "[DLQ] Capacity "
                            + capacity
                            + " reached — evicting oldest entry: "
                            + (evicted != null ? evicted.event().getClass().getSimpleName() : "null")
                            + ". New entry: "
                            + entry.event().getClass().getSimpleName());
        }
        queue.add(entry);
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
    public synchronized List<DeadLetterEntry> drain() {
        List<DeadLetterEntry> result = new ArrayList<>(queue);
        queue.clear();
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized int size() {
        return queue.size();
    }
}

package net.nexus_flow.core.cqrs.event;

/**
 * Per-listener deduplication contract. Prevents reprocessing the same event by a listener that has
 * already handled it (based on {@link net.nexus_flow.core.ddd.DomainEvent#idempotencyKey()}).
 *
 * <p>Implementations:
 *
 * <ul>
 * <li>{@link BoundedInMemoryEventDeduplicator} — LRU-bounded in-memory store. Suitable for
 * in-process deduplication where the event window fits in memory.
 * <li>External adapter modules (e.g. Redis {@code SET NX EX} via {@code
 *       nexus-flow-redis-lettuce}, or a JDBC unique-constraint table via {@code nexus-flow-jdbc})
 * implement this contract directly. There is no in-core placeholder for the distributed case.
 * </ul>
 *
 * {@snippet :
 * var deduplicator = new BoundedInMemoryEventDeduplicator(10_000);
 * var listener = new OrderAuditListener();
 * eventBus.register(listener, deduplicator);
 * }
 */
public interface EventDeduplicator {

    /** Returns {@code true} if this key has already been processed. Must be thread-safe. */
    boolean isDuplicate(String idempotencyKey);

    /** Marks the key as processed. Called AFTER successful handling. Must be thread-safe. */
    void markSeen(String idempotencyKey);
}

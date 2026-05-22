package net.nexus_flow.core.cqrs.event;

import java.util.List;

/**
 * Sink for event dispatch failures that have exhausted all retry and error-handler options.
 *
 * <p>Two implementations are provided:
 *
 * <ul>
 * <li>{@link InMemoryDeadLetterQueue} — bounded in-process ring buffer. Suitable for development,
 * testing, and low-volume production with manual drain.
 * <li>External adapter modules (e.g. a Kafka dead-letter topic via {@code nexus-flow-kafka-dlq},
 * or a JDBC {@code dead_letters} table via {@code nexus-flow-jdbc}) implement this contract
 * directly. There is no in-core placeholder for the persistent case.
 * </ul>
 *
 * {@snippet :
 * InMemoryDeadLetterQueue dlq = new InMemoryDeadLetterQueue(500);
 * FlowRuntime runtime = FlowRuntime.builder()
 *         .deadLetterQueue(dlq)
 *         .build();
 *
 * // Later, inspect or drain queued failures.
 * dlq.drain().forEach(entry -> LOG.warning(
 *                                          "DLQ: " + entry.event().getClass().getSimpleName()));
 * }
 */
public interface DeadLetterQueue {

    /**
     * Enqueue a failed entry. Implementations must be thread-safe. Bounded implementations SHOULD log
     * a warning and drop the oldest entry on overflow.
     */
    void enqueue(DeadLetterEntry entry);

    /** Drain and return all currently queued entries, clearing the queue atomically. */
    List<DeadLetterEntry> drain();

    /** Current number of entries waiting in the queue. */
    int size();

    /** {@code true} when the queue has no entries. */
    default boolean isEmpty() {
        return size() == 0;
    }
}

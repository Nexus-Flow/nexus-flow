package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;

/**
 * Extension of {@link DomainEventListener} that declares a <em>partition key</em> for sharded,
 * per-key-ordered event delivery — analogous to Kafka's message key for partition assignment.
 *
 * <h2>In-process sharding contract</h2>
 *
 * <p>The in-process {@link DefaultEventBus} routes events to {@code PartitionedEventListener}
 * instances by:
 *
 * <ol>
 * <li>Computing the target partition index: {@code Math.floorMod(partitionKey(event).hashCode(),
 *       partitionCount())}.
 * <li>Invoking the listener instance's {@code handle(event)} only when {@link #partitionIndex()}
 * equals the target.
 * </ol>
 *
 * <p>The framework does not auto-assign partition slots: each listener instance owns the {@link
 * #partitionIndex()} it returns. Operators register N sibling instances (typically with the same
 * listener class but different state buckets) and configure each instance with a distinct index
 * from {@code [0, partitionCount())}. All siblings MUST agree on {@link #partitionCount()}; the
 * framework does not enforce this at registration time but a mismatch will cause some partitions to
 * be unowned.
 *
 * <h2>Single-instance default</h2>
 *
 * <p>The default {@link #partitionCount()} is {@code 1} and {@link #partitionIndex()} is {@code 0}.
 * A single instance with the defaults receives every event (the partition check trivially passes:
 * {@code anything mod 1 == 0}). This makes adding {@code PartitionedEventListener} to an existing
 * listener safe without further configuration; only multi-instance sharded deployments need to
 * override both methods.
 *
 * <h2>Distributed sharding</h2>
 *
 * <p>The in-process bus only solves the SINGLE-JVM, multi-instance case. For multi-JVM /
 * multi-replica sharding (where each pod / process owns one or more partitions), front the bus with
 * a sharding-aware broker (Kafka consumer groups, Redis Streams consumer groups, RabbitMQ
 * consistent-hash exchange) in an adapter module. The contract above remains the same; the broker
 * just selects which JVM receives which partition.
 *
 * <p>
 *
 * {@snippet :
 *   public class ShardedOrderListener extends AbstractDomainEventListener<OrderPlaced>
 *       implements PartitionedEventListener<OrderPlaced> {
 *
 *     private final int index;
 *
 *     ShardedOrderListener(int index) { this.index = index; }
 *
 *     &#64;Override
 *     public String partitionKey(OrderPlaced event) {
 *       return event.customerId(); // same customer -> same shard
 *     }
 *
 *     &#64;Override public int partitionCount() { return 4; }
 *     &#64;Override public int partitionIndex() { return index; }
 *
 *     &#64;Override
 *     public void handle(OrderPlaced event) { ... }
 *   }
 *   // Register 4 sibling instances:
 *   for (int i = 0; i < 4; i++) eventBus.register(new ShardedOrderListener(i));
 * }
 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface PartitionedEventListener<E extends DomainEvent> {

    /**
     * Returns the partition key for the given event. Events with the same key are routed to the
     * single sibling whose {@link #partitionIndex()} equals {@code Math.floorMod(key.hashCode(),
     * partitionCount())}.
     *
     * @param event the event being dispatched
     * @return a non-null, stable partition key string
     */
    String partitionKey(E event);

    /**
     * The total number of partition slots this listener pool is divided into. Must be {@code >= 1};
     * default {@code 1} (no sharding — a single instance handles every event).
     *
     * <p>All sibling instances of the same logical pool MUST return the same value. A mismatch leaves
     * some partitions unowned (events for those partitions are silently dropped).
     *
     * @return the total partition count
     */
    default int partitionCount() {
        return 1;
    }

    /**
     * The partition slot this specific instance owns. Must be in {@code [0, partitionCount())};
     * default {@code 0}.
     *
     * <p>Each registered sibling MUST return a distinct index. The framework does not validate this
     * at registration time — duplicate indices silently double-process those partitions and missing
     * indices silently drop them.
     *
     * @return the slot index owned by this instance
     */
    default int partitionIndex() {
        return 0;
    }
}

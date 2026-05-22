package net.nexus_flow;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory order repository for the demo.
 *
 * <p>This class models the seam where a production application would use an event-sourced {@code
 * AggregateRepository} or another durable persistence adapter. It is intentionally simple: handlers
 * and query handlers share the same instance inside {@link NexusFlowDemo}.
 */
public final class InMemoryOrderRepository {

  private final Map<String, OrderAggregate> orders = new ConcurrentHashMap<>();

  /**
   * Stores or replaces an order aggregate by id.
   *
   * @param order aggregate to store
   */
  public void save(OrderAggregate order) {
    orders.put(order.getOrderId(), order);
  }

  /**
   * Finds an order by id.
   *
   * @param orderId order id
   * @return matching aggregate, if present
   */
  public Optional<OrderAggregate> findById(String orderId) {
    return Optional.ofNullable(orders.get(orderId));
  }
}
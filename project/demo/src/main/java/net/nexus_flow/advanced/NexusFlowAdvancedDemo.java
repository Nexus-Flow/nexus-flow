package net.nexus_flow.advanced;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.outbox.DeadLetterHandler;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxConfig;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.interceptors.LoggingDispatchInterceptor;

/**
 * Advanced Nexus Flow demo — exercises the full stack end-to-end inside a single JVM:
 *
 * <ul>
 * <li><strong>Outbox</strong> — every recorded domain event is written through {@link
 * net.nexus_flow.core.outbox.OutboxAppender} into an {@link InMemoryOutboxStorage} (substitute
 * a JDBC storage in production).
 * <li><strong>Worker</strong> — the runtime's bundled outbox worker drains the storage on a poll
 * loop, dispatches each row through the {@link net.nexus_flow.core.cqrs.event.EventBus}, and
 * marks the row published on success.
 * <li><strong>Listeners drive the saga</strong> — {@code StockService} reacts to
 * {@code OrderPlacedEvent} and emits {@code StockReservedEvent} (success) or
 * {@code StockReservationFailedEvent} (failure); {@code PaymentService} and
 * {@code CompensationService} continue the chain.
 * <li><strong>Dead-letter</strong> — failures that exhaust the retry budget surface through a
 * custom {@link DeadLetterHandler} that logs the offending row + cause.
 * <li><strong>Audit trail</strong> — a catch-all listener prints every event so the flow is
 * observable in the console.
 * </ul>
 *
 * <p>Two scenarios run:
 *
 * <ol>
 * <li><strong>Happy path</strong> — small order, stock reserved, payment charged, saga
 * completes.
 * <li><strong>Compensation path</strong> — oversized order, stock fails, compensation cancels
 * the order.
 * </ol>
 *
 * <p>The demo is single-process for clarity. The ring path (cross-pod fan-out) is exercised by
 * {@code CombinedFlowIntegrationTest} and {@code RingRuntimeTest}; running the ring in a demo
 * requires two JVMs (or two threads with loopback sockets) and is intentionally kept out of
 * this in-process walk-through.
 */
public final class NexusFlowAdvancedDemo {

    private static final Logger LOG = System.getLogger(NexusFlowAdvancedDemo.class.getName());

    private NexusFlowAdvancedDemo() {
        // utility
    }

    static void main() {
        InMemoryOutboxStorage outboxStorage = new InMemoryOutboxStorage();

        // Handlers and listeners no longer wire the outbox themselves — they just record
        // events on the aggregate and the runtime's recursive drain takes care of appending
        // and fanning out. The handlers/listeners share one Map<String, OrderAggregate> as a
        // poor-man's repository; real production uses
        // AggregateRepository.builder(...).outbox(cfg).build() so save() appends to the event
        // store and the outbox in the same logical transaction.
        PlaceOrderHandler   placeHandler  = new PlaceOrderHandler();
        StockService        stockListener = new StockService(
                                                             placeHandler.orders(), /* tries before first failure */ 1);
        PaymentService      payment       = new PaymentService(placeHandler.orders());
        CompensationService compensation  = new CompensationService(placeHandler.orders());

        DeadLetterHandler customDeadLetter = (row, cause) ->
                LOG.log(Level.ERROR,
                        "[DEAD-LETTER] row={0} type={1} cause={2}",
                        row.outboxId(),
                        row.payloadType().getSimpleName(),
                        cause.getMessage());

        // Default delivery strategy is EventDeliveryStrategy.outboxOnly() — the worker is the
        // sole publisher and the framework's worker-side recursive drain captures any events
        // the listeners emit through aggregate mutation (StockService → markStockReserved →
        // OrderAggregate emits StockReservedEvent) and appends them back into the outbox for
        // the next poll cycle. Each listener observes every event exactly once, the cascade
        // propagates fully, and no InboxStorage dedup is needed.
        OutboxConfig outboxConfig = OutboxConfig.builder(
                                                        outboxStorage,
                                                        new JavaSerializationOutboxPayloadCodec())
                .workerPollInterval(Duration.ofMillis(50))
                .workerMaxAttempts(3)
                .workerBackoffBase(Duration.ofMillis(10))
                .workerBackoffMax(Duration.ofMillis(100))
                .drainOnShutdown(true)
                .deadLetterHandler(customDeadLetter)
                .build();

        try (FlowRuntime runtime = FlowRuntime.builder()
                .interceptor(new LoggingDispatchInterceptor())
                .outbox(outboxConfig)
                .handlers(
                        new AuditTrailListener(),
                        placeHandler,
                        stockListener,
                        payment,
                        compensation)
                .build()) {

            LOG.log(Level.INFO, "==== Scenario 1 — happy path ====");
            runtime.commands().dispatch(
                                        Command.<PlaceOrderCommand>builder()
                                                .body(new PlaceOrderCommand("order-001", "prod-42", 3))
                                                .build());

            LOG.log(Level.INFO, "==== Scenario 2 — compensation path (oversized order) ====");
            runtime.commands().dispatch(
                                        Command.<PlaceOrderCommand>builder()
                                                .body(new PlaceOrderCommand("order-002", "prod-42", 999))
                                                .build());

            // Notification-based wait: the bundled outbox worker signals a JDK Condition
            // every time it observes quiescence (zero PENDING rows AND zero in-flight
            // dispatches). The caller wakes only when the worker has actually drained
            // everything — no Thread.sleep heuristic, no hand-tuned poll budget.
            runtime.awaitOutboxIdle(Duration.ofSeconds(2));
        }
        // FlowRuntime.close() drains the outbox on shutdown (drainOnShutdown=true).
        LOG.log(Level.INFO, "==== Demo finished — runtime closed cleanly ====");
    }
}

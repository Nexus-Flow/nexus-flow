# Nexus Flow

**A lightweight Java library for Domain-Driven Design (DDD), CQRS, and Event-Driven Architecture (EDA).**

Nexus Flow gives you a clean, testable, and production-ready programming model for commands, queries, and domain events — backed by virtual threads, structured concurrency, event sourcing, a transactional outbox, and saga support.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Core Concepts](#core-concepts)
   - [FlowRuntime](#flowruntime)
   - [Commands and Command Handlers](#commands-and-command-handlers)
   - [Queries and Query Handlers](#queries-and-query-handlers)
   - [Aggregates and Domain Events](#aggregates-and-domain-events)
   - [Event Listeners](#event-listeners)
3. [Advanced Features](#advanced-features)
   - [DispatchResult and ErrorPolicy](#dispatchresult-and-errorpolicy)
   - [ExecutionContext, Deadlines and Cancellation](#executioncontext-deadlines-and-cancellation)
   - [Event Ordering and Idempotency](#event-ordering-and-idempotency)
   - [Event Sourcing](#event-sourcing)
   - [Transactional Outbox and Inbox Deduplication](#transactional-outbox-and-inbox-deduplication)
   - [Sagas and Process Managers](#sagas-and-process-managers)
   - [Observability](#observability)
4. [Domain Example: Order Management](#domain-example-order-management)
5. [Capabilities Delivered](#capabilities-delivered)
6. [Contributing](#contributing)

---

## Quick Start

```java
InMemoryOrderRepository orders = new InMemoryOrderRepository();

try (FlowRuntime runtime =
        FlowRuntime.builder().interceptor(new LoggingDispatchInterceptor()).build()) {

        // Register handlers
        runtime.events().register(new OrderAuditListener());
        runtime.events().register(new OrderCancellationAuditListener());
        runtime.commands().register(new PlaceOrderCommandHandler(orders));
        runtime.commands().register(new CancelOrderCommandHandler(orders));
        runtime.queries().register(new GetOrderQueryHandler(orders));

        // Dispatch a command (fire-and-forget)
        runtime.commands()
      .dispatch(
        Command.<PlaceOrderCommand>builder()
              .body(new PlaceOrderCommand("order-001", "prod-42", 3))
        .build());

// Typed-result command with full DispatchResult
DispatchResult<?> result =
        runtime.commands()
                .dispatchAndReturnResult(
                        Command.<CancelOrderCommand>builder()
                                .body(new CancelOrderCommand("order-001", "Customer request"))
                                .build(),
                        ExecutionContext.root(),
                        ErrorPolicy.failFast());

  switch (result) {
        case DispatchResult.Success<?> success -> System.out.println("Done: " + success.value());
        case DispatchResult.Failure<?> failure ->
        System.err.println("Error: " + failure.cause().getMessage());
        case DispatchResult.PartialFailure<?> partialFailure ->
        System.err.println("Partial: " + partialFailure.failures().size() + " errors");
        case DispatchResult.Accepted<?> accepted ->
        System.out.println("Accepted: " + accepted.messageId());
        }

// Query
String summary =
        runtime.queries()
                .ask(Query.<GetOrderQuery>builder().body(new GetOrderQuery("order-001")).build());
  System.out.println(summary);
}
```

---

## Core Concepts

### FlowRuntime

`FlowRuntime` is the single owner of all buses, executors, and lifecycle resources. Every application should create **one** `FlowRuntime` instance (or one per bounded context) and close it when the process shuts down.

```java
FlowRuntime runtime = FlowRuntime.builder()
    .interceptor(new LoggingDispatchInterceptor())   // log every dispatch
    .parallelListeners(true)                         // opt-in parallel event fan-out
    .shutdownTimeout(Duration.ofSeconds(10))         // graceful shutdown timeout
    .build();
```

---

### Commands and Command Handlers

A **command** represents an intention to change state. It carries its payload as a Java `record`.

```java
// Command body — plain Java record
public record PlaceOrderCommand(String orderId, String productId, int quantity) {}

// No-return handler
public class PlaceOrderCommandHandler extends AbstractNoReturnCommandHandler<PlaceOrderCommand> {

    @Override
    protected void handle(PlaceOrderCommand cmd) {
        OrderAggregate order = OrderAggregate.place(cmd.orderId(), cmd.productId(), cmd.quantity());
        // repository.save(order);
    }
}

// Return-value handler
public class ReserveStockHandler extends AbstractReturnCommandHandler<ReserveStockCommand, ReservationId> {

    @Override
    protected ReservationId handle(ReserveStockCommand cmd) {
        // perform reservation …
        return new ReservationId(UUID.randomUUID());
    }
}
```

**Registration and dispatch:**

```java
runtime.commands().register(new PlaceOrderCommandHandler());

// Fire-and-forget
runtime.commands().dispatch(
    Command.<PlaceOrderCommand>builder()
        .body(new PlaceOrderCommand("order-001", "prod-42", 3))
        .priority(10)
        .build());

// With typed return
ReservationId id = runtime.commands().dispatchAndReturn(
    Command.<ReserveStockCommand>builder().body(new ReserveStockCommand("prod-42", 3)).build());
```

**Handler policy overrides** (optional; configure per handler):

```java
public class PlaceOrderCommandHandler extends AbstractNoReturnCommandHandler<PlaceOrderCommand> {

    @Override public int  getConcurrencyLevel()     { return 4; }          // max parallel executions
    @Override public boolean isSagaEnabled()        { return false; }      // inline execution
    @Override public InitializationType getInitializationType() { return InitializationType.EAGER; }
}
```

---

### Queries and Query Handlers

A **query** is a read-only request. It never changes state.

```java
public record GetOrderQuery(String orderId) {}

public class GetOrderQueryHandler extends AbstractQueryHandler<GetOrderQuery, String> {

    @Override
    public String handle(GetOrderQuery query) {
        // hit a read model or projection
        return readModel.findSummaryById(query.orderId());
    }
}
```

```java
runtime.queries().register(new GetOrderQueryHandler());

String summary = runtime.queries().ask(
    Query.<GetOrderQuery>builder().body(new GetOrderQuery("order-001")).build());
```

---

### Aggregates and Domain Events

Aggregates own their state and enforce business invariants. They record **domain events** — facts about what happened — rather than mutating external state directly.

```java
public class OrderAggregate extends Aggregate {

    private String status = "PENDING";

    public static OrderAggregate place(String orderId, String productId, int qty) {
        var order = new OrderAggregate();
        order.status = "PLACED";
        order.recordEvent(new OrderPlacedEvent(orderId, productId, qty));
        return order;
    }

    public void cancel(String reason) {
        if (!"PLACED".equals(status))
            throw new IllegalStateException("Cannot cancel a " + status + " order");
        status = "CANCELLED";
        recordEvent(new OrderCancelledEvent(getOrderId(), reason));
    }

    // Required for event-sourcing replay
    @Override
    protected void apply(DomainEvent event) {
        switch (event) {
            case OrderPlacedEvent    e -> status = "PLACED";
            case OrderCancelledEvent e -> status = "CANCELLED";
            default -> {}
        }
    }
}
```

```java
public class OrderPlacedEvent extends AbstractDomainEvent {

    private final String productId;
    private final int quantity;

    public OrderPlacedEvent(String orderId, String productId, int quantity) {
        super(orderId);          // aggregateId
        this.productId = productId;
        this.quantity  = quantity;
    }

    public String getProductId() { return productId; }
    public int    getQuantity()  { return quantity; }
}
```

After the handler returns, the runtime drains the aggregate's uncommitted events and dispatches them to every registered listener through the `EventBus`.

---

### Event Listeners

Event listeners react to domain events. Register one listener per event type; use the `order()` method to control fan-out sequencing.

```java
public class OrderAuditListener extends AbstractDomainEventListener<OrderPlacedEvent> {

    @Override
    public void handle(OrderPlacedEvent event) {
        // write to audit log, update read model, send confirmation email, etc.
        System.out.printf("[AUDIT] Order %s placed — product %s x%d%n",
            event.getOrderId(), event.getProductId(), event.getQuantity());
    }

    @Override
    public int order() { return 1; }  // lower = runs first
}
```

**Fan-out ordering rules:**

- Listeners with the same `order()` value run in registration order by default.
- Opt-in parallel fan-out: override `parallelSafe()` → `true` and enable `FlowRuntime.builder().parallelListeners(true)`.

---

## Advanced Features

### DispatchResult and ErrorPolicy

Every typed-result dispatch returns a sealed `DispatchResult<T>`. Use `ErrorPolicy` to control how failures are accumulated.

```java
DispatchResult<Void> result = runtime.commands().dispatchAndReturnResult(
    cmd,
    ExecutionContext.root(),
    ErrorPolicy.collectFailures());   // continue on first failure, aggregate all

switch (result) {
     case DispatchResult.Success<Void>        s -> { /* happy path */ }
     case DispatchResult.Failure<Void>        f -> log.error("Failed", f.cause());
     case DispatchResult.PartialFailure<Void> p -> p.failures().forEach(e -> log.warn("Partial", e));
     case DispatchResult.Accepted<Void>       a -> { /* queued for durable processing */ }
}
```

Available policies:

| Policy | Behaviour |
|---|---|
| `ErrorPolicy.failFast()` | Abort on the first failure; cancel sibling work. |
| `ErrorPolicy.collectFailures()` | Continue; aggregate all failures into `PartialFailure`. |
| `ErrorPolicy.ignoreFailures(predicate)` | Selectively ignore matching exceptions. |
| `ErrorPolicy.isolate(inner)` | Failures in event listeners do not propagate to the parent command. |

---

### ExecutionContext, Deadlines and Cancellation

`ExecutionContext` carries trace / correlation IDs, deadlines, and cancellation tokens through the entire call chain.

```java
ExecutionContext ctx = ExecutionContext.root()
    .withDeadline(Instant.now().plus(Duration.ofSeconds(5)))
    .withCorrelationId(correlationId);

DispatchResult<?> result = runtime.commands().dispatchAndReturnResult(cmd, ctx, ErrorPolicy.failFast());
```

Child commands and events dispatched during handler execution automatically receive child contexts derived from the parent, preserving the full trace hierarchy.

---

### Event Ordering and Idempotency

Every domain event records a monotonically increasing **sequence number** per aggregate instance (starting at 0). The default `idempotencyKey` is `aggregateId + ":" + sequenceNumber`, which is stable across replays and process restarts.

```java
event.getSequenceNumber();   // 0, 1, 2 … per aggregate instance
event.idempotencyKey();      // e.g. "order-001:0"
```

Override `idempotencyKey()` on external/integration events to return the upstream message ID verbatim.

---

### Event Sourcing

Wire an `AggregateRepository` to persist and replay aggregates from an `EventStore`:

```java
EventStore        store      = new InMemoryEventStore();
SnapshotStore     snapshots  = new InMemorySnapshotStore();

AggregateRepository<OrderAggregate> repo = AggregateRepository
    .builder(store, OrderAggregate.class, OrderAggregate::new)
    .snapshotStore(snapshots)
    .snapshotEvery(50)           // auto-snapshot every 50 events
    .build();

// Save
OrderAggregate order = OrderAggregate.place("order-001", "prod-42", 3);
repo.save(order);                // appends events; throws OptimisticConcurrencyException on conflict

// Load — replays events from the store (or hydrates from the latest snapshot)
OrderAggregate loaded = repo.load(UUID.fromString("order-001"));
```

---

### Transactional Outbox and Inbox Deduplication

For durable, at-least-once event delivery across process boundaries, wire an `OutboxStorage` and configure `ExecutionMode.AsynchronousDurable`:

```java
OutboxStorage  outbox = new InMemoryOutboxStorage();
InboxStorage   inbox  = new InMemoryInboxStorage();

FlowRuntime runtime = FlowRuntime.builder()
    .outbox(OutboxConfig.builder()
        .storage(outbox)
        .inbox(inbox)
        .drainOnShutdown(true)
        .build())
    .build();

// The OutboxWorker polls the outbox, delivers events through the EventBus,
// and deduplicates delivery using the inbox (messageId, consumerId) key.
```

The `OutboxRecord` status lifecycle:

```text
PENDING → IN_FLIGHT → PUBLISHED
                    → FAILED (retryable, exponential backoff with jitter)
                    → FAILED_TERMINAL (manual replay required)
```

---

### Sagas and Process Managers

Long-running, multi-step business processes are modelled as `Saga<K>` implementations:

```java
public class OrderFulfillmentSaga implements Saga<String> {

    @Override
    public String correlate(DomainEvent event) {
        return switch (event) {
            case OrderPlacedEvent    e -> e.getOrderId();
            case PaymentConfirmedEvent e -> e.getOrderId();
            default -> null;   // not correlated
        };
    }

    @Override
    public SagaTransition handle(SagaState<String> state, DomainEvent event) {
        return switch (event) {
            case OrderPlacedEvent    e -> SagaTransition.advance(new ConfirmPaymentCommand(e.getOrderId()));
            case PaymentConfirmedEvent e -> SagaTransition.complete();
            default -> SagaTransition.skip();
        };
    }

    @Override
    public SagaTransition compensate(SagaState<String> state, Throwable cause) {
        return SagaTransition.compensate(new RefundPaymentCommand(state.correlationKey()));
    }
}
```

`SagaRunner` subscribes to the `EventStore`'s global position log, drives catch-up, persists state under optimistic concurrency, and routes compensation events through the outbox.

---

### Observability

**JFR (Java Flight Recorder):** Three built-in events are emitted automatically for every dispatch:

| JFR Event | Fields |
|---|---|
| `net.nexusflow.CommandDispatch` | `commandType`, `outcome`, `failureClass` |
| `net.nexusflow.EventPublish` | `eventType`, `listenerCount`, `parallelFanOut`, `outcome` |
| `net.nexusflow.HandlerInvoke` | `targetType`, `handlerType`, `success`, `failureClass` |

Enable a JFR recording and filter on category `NexusFlow/CQRS`.

**Dispatch interceptors:** Chain custom interceptors for logging, tracing, or metrics:

```java
FlowRuntime.builder()
    .interceptor(new LoggingDispatchInterceptor())
    .interceptor(myTracingInterceptor)
    .build();
```

---

## Domain Example: Order Management

The `project/demo` module contains a runnable end-to-end example of the Order Management domain:

| File | Role |
|---|---|
| `PlaceOrderCommand` | Command body record |
| `CancelOrderCommand` | Command body record |
| `GetOrderQuery` | Query body record |
| `OrderAggregate` | Aggregate — state machine with `place()` and `cancel()` |
| `OrderPlacedEvent` | Domain event emitted by `OrderAggregate.place()` |
| `OrderCancelledEvent` | Domain event emitted by `OrderAggregate.cancel()` |
| `InMemoryOrderRepository` | Demo-only in-memory persistence seam shared by handlers and queries |
| `CancellationReceipt` | Typed result returned by `CancelOrderCommandHandler` |
| `PlaceOrderCommandHandler` | No-return handler for `PlaceOrderCommand` |
| `CancelOrderCommandHandler` | Return-value handler for `CancelOrderCommand` |
| `GetOrderQueryHandler` | Query handler returning an order summary from the demo repository |
| `OrderAuditListener` | Event listener writing placed-order audit log entries |
| `OrderCancellationAuditListener` | Event listener writing cancelled-order audit log entries |
| `NexusFlowDemo` | `main()` — bootstraps the runtime and exercises all paths |

Run the demo:

```bash
./gradlew :project:demo:runDemo
```

Expected output includes:

```text
Cancel succeeded: CancellationReceipt[orderId=order-001, status=CANCELLED, reason=Customer request]
Order summary: Order[id=order-001, status=CANCELLED, product=prod-42, qty=3]
```

---

## Capabilities Delivered

| Area | What's in the box |
|---|---|
| Smoke & lifecycle | Executor lifecycle fixes, `ScopedDomainEventContext` |
| Core runtime | `FlowRuntime`, `DispatchResult`, `ErrorPolicy`, `ExecutionContext`, aggregate-owned events |
| Dispatch & back-pressure | Pluggable execution strategies, virtual threads, deadlines, cancellation, back-pressure, outbox contract |
| Handler registry | `HandlerRegistry` + `ClassValue`-cached `DispatchPlan`, `MethodHandle` invokers, benchmarks |
| Event sourcing & sagas | `EventStore`, `AggregateRepository`, snapshots, projections, transactional outbox/inbox, sagas |
| Observability & ops | JFR events, `System.Logger`, `ScheduledCommandWorker`, `snapshotEvery`, parallel fan-out, graceful shutdown |

---

## Contributing

We welcome contributions! To get started:

1. Fork the repository.
2. Create a new branch for your feature or bug fix.
3. Make your changes and ensure `./gradlew :project:core:test` stays green.
4. Submit a pull request to the `main` branch.

See [CONTRIBUTING.adoc](CONTRIBUTING.adoc) for full guidelines.

## Issues

If you encounter a bug or have a feature suggestion, please [open an issue](https://github.com/Nexus-Flow/nexus-flow/issues).

# Nexus Flow

**A Java 25 runtime for building event-driven services at scale — CQRS, DDD, Event Sourcing, transactional outbox, sagas, and distributed fan-out from a single dependency.**

---

## Why Nexus Flow

Most CQRS/ES frameworks ask you to wire a command bus, an event bus, an outbox table, a saga engine, and a distributed topic — independently, with different lifecycle contracts, different serialization rules, and different error models. You end up with glue code that is harder to test than the business logic it carries.

Nexus Flow ships **all of it as one coherent runtime.** One `FlowRuntime` instance owns the lifecycle. Commands, queries, events, outbox, inbox deduplication, sagas, and the distributed ring transport share the same execution context, cancellation token, error policy, and observability pipeline. When a handler records a domain event, the outbox, the saga engine, and the event fan-out are all already wired. There is nothing to glue.

It is designed to sustain **millions of in-process dispatches per second per JVM** — the hot path is lock-free, `ClassValue`-cached, and `MethodHandle`-invoked. Adding it to a greenfield service or layering it over a Spring/Quarkus application is intentionally low-friction.

---

## At a glance

```
Commands → FlowRuntime → Aggregate → Events ──► EventBus ──► Listeners
                │                                    │
                ▼                                    ▼
         HandlerRegistry              OutboxWorker polls storage
         (ClassValue plan)         and re-publishes with dedup
                │
                ▼
        SagaRunner checkpoints
        and drives compensation
                │
                ▼
         Ring transport (optional)
         fans out to peer pods via
         mTLS socket channels
```

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Architecture](#architecture)
3. [Core Concepts](#core-concepts)
   - [FlowRuntime](#flowruntime)
   - [Commands](#commands)
   - [Queries](#queries)
   - [Aggregates and Domain Events](#aggregates-and-domain-events)
   - [Event Listeners](#event-listeners)
4. [Advanced Features](#advanced-features)
   - [DispatchResult and ErrorPolicy](#dispatchresult-and-errorpolicy)
   - [ExecutionContext, Deadlines and Cancellation](#executioncontext-deadlines-and-cancellation)
   - [Transactional Outbox and At-Least-Once Delivery](#transactional-outbox-and-at-least-once-delivery)
   - [Inbox Deduplication](#inbox-deduplication)
   - [Event Sourcing](#event-sourcing)
   - [Sagas and Process Managers](#sagas-and-process-managers)
   - [Observability](#observability)
5. [Distributed Ring Transport](#distributed-ring-transport)
6. [Demos](#demos)
7. [Performance](#performance)
8. [Adapter Modules (Roadmap)](#adapter-modules-roadmap)
9. [Build and Quality Gates](#build-and-quality-gates)
10. [Contributing](#contributing)

---

## Quick Start

**Maven / Gradle coordinate** — publish to local Maven first:

```bash
./gradlew publishToMavenLocal
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.nexus_flow:nexusflow-core:0.1.0-SNAPSHOT")
}
```

**Minimal runtime — command + query + event listener in ~20 lines:**

```java
try (FlowRuntime runtime = FlowRuntime.builder()
        .interceptor(new LoggingDispatchInterceptor())
        .handlers(
            new PlaceOrderCommandHandler(orders),
            new GetOrderQueryHandler(orders),
            new OrderAuditListener())
        .build()) {

    // Fire-and-forget command
    runtime.commands().dispatch(
        Command.<PlaceOrderCommand>builder()
               .body(new PlaceOrderCommand("order-001", "prod-42", 3))
               .build());

    // Typed-result command
    DispatchResult<CancellationReceipt> result = runtime.commands().dispatchAndReturnResult(
        Command.<CancelOrderCommand>builder()
               .body(new CancelOrderCommand("order-001", "Customer request"))
               .build(),
        ExecutionContext.root(),
        ErrorPolicy.failFast());

    switch (result) {
        case DispatchResult.Success<CancellationReceipt> s -> System.out.println(s.value());
        case DispatchResult.Failure<?>                  f -> System.err.println(f.cause());
        case DispatchResult.Accepted<?>                 a -> System.out.println("queued: " + a.messageId());
        case DispatchResult.PartialFailure<?>           p -> p.failures().forEach(System.err::println);
    }

    // Query
    String summary = runtime.queries().ask(
        Query.<GetOrderQuery>builder()
             .body(new GetOrderQuery("order-001"))
             .build());
}
// FlowRuntime.close() shuts down workers and drains the outbox cleanly.
```

---

## Architecture

```
+----------------------------------------------------------------------+
|  FlowRuntime  (one per bounded context)                              |
|                                                                      |
|   Command Bus      Query Bus              Event Bus                  |
|       |               |       fan-out / idempotency / ordering       |
|       v               v                     v                        |
|  +----------------------------------------------------------------+  |
|  |  HandlerRegistry   (ClassValue-cached DispatchPlan)            |  |
|  |  DispatchInterceptor chain   |   MethodHandle invokers         |  |
|  |  ExecutionContext propagation  |  Virtual Thread executor      |  |
|  +----------------------------------------------------------------+  |
|                                                                      |
|  +----------------------------------------------------------------+  |
|  |  Aggregate runtime                                             |  |
|  |  EventStore     |  AggregateRepository  |  Snapshots           |  |
|  |  OutboxAppender |  InboxStorage         |  OutboxWorker        |  |
|  |  SagaRunner     |  ScheduledCommandWorker                      |  |
|  +----------------------------------------------------------------+  |
|                                                                      |
|  +----------------------------------------------------------------+  |
|  |  RingRuntime  (optional, attach via .attachTo(flowRuntime))    |  |
|  |  mTLS peer-to-peer sockets  |  consistent-hash routing         |  |
|  |  live fan-out (RingEventBusBridge)                             |  |
|  |  durable fan-out (RingOutboxBridge)  |  RingOps health facade  |  |
|  +----------------------------------------------------------------+  |
+----------------------------------------------------------------------+
```

**Layer rules (strictly enforced):**

| Layer | Package | What lives here |
|---|---|---|
| Domain | `core.ddd` | `Aggregate`, `AggregateRoot`, `DomainEvent`, `AbstractDomainEvent` |
| CQRS contracts | `core.cqrs.*` | Buses, handlers, listeners, annotations, introspection |
| Runtime | `core.runtime.*` | `FlowRuntime`, `ExecutionContext`, `DispatchPlan`, `MethodHandle` invokers |
| Event sourcing | `core.eventsourcing` | `EventStore`, `AggregateRepository`, snapshots, projections |
| Messaging | `core.outbox`, `core.inbox` | `OutboxStorage`, `InboxStorage`, `OutboxWorker`, codec SPI |
| Process managers | `core.saga` | `Saga<K>`, `SagaRunner`, `SagaState`, `SagaStorage` |
| Scheduling | `core.scheduling` | `ScheduledCommandStorage`, `ScheduledCommandWorker` |
| Observability | `core.observability` | `Observability`, `MetricsRecorder`, `TracingBridge`, JFR events |
| Ring transport | `core.ring.*` | Peer sockets, frame protocol, membership, routing, bridges, ops |

---

## Core Concepts

### FlowRuntime

`FlowRuntime` is the **single lifecycle root**. It owns all buses, executors, workers, and observability sinks. There is no static state, no `getInstance()`, no shared process-globals. Two runtimes in the same JVM are fully isolated.

```java
FlowRuntime runtime = FlowRuntime.builder()
    .interceptor(new LoggingDispatchInterceptor())
    .parallelListeners(true)        // opt-in parallel event fan-out
    .shutdownTimeout(Duration.ofSeconds(10))
    .handlers(
        new PlaceOrderCommandHandler(),
        new CancelOrderCommandHandler(),
        new GetOrderQueryHandler(),
        new OrderAuditListener())   // all types registered in one call
    .build();
```

`FlowRuntime.builder().handlers(Object...)` accepts any mix of `NoReturnCommandHandler`, `ReturnCommandHandler`, `DomainEventListener`, or `AbstractQueryHandler` and routes each to the correct bus. This covers the common case; individual buses remain accessible for more granular control.

`FlowRuntime#close()` is idempotent and drains the outbox (if `drainOnShutdown(true)`). `FlowRuntime#shutdown(Duration)` is the Kubernetes pre-stop hook override.

---

### Commands

Commands represent intent to change state. They carry their payload as a Java record.

```java
public record PlaceOrderCommand(String orderId, String productId, int quantity) {}

// No return value
public class PlaceOrderCommandHandler
        extends AbstractNoReturnCommandHandler<PlaceOrderCommand> {

    @Override
    protected void handle(PlaceOrderCommand cmd) {
        var order = OrderAggregate.place(cmd.orderId(), cmd.productId(), cmd.quantity());
        repository.save(order);
    }
}

// With typed return
public class CancelOrderCommandHandler
        extends AbstractReturnCommandHandler<CancelOrderCommand, CancellationReceipt> {

    @Override
    protected CancellationReceipt handle(CancelOrderCommand cmd) {
        OrderAggregate order = repository.load(cmd.orderId());
        order.cancel(cmd.reason());
        repository.save(order);
        return new CancellationReceipt(cmd.orderId(), "CANCELLED", cmd.reason());
    }
}
```

**Per-handler policy overrides** (all optional):

```java
public class PlaceOrderCommandHandler
        extends AbstractNoReturnCommandHandler<PlaceOrderCommand> {

    @Override public int  getConcurrencyLevel()   { return 8; }
    @Override public long getMaxQueueSize()        { return 512; }
    @Override public SaturationPolicy getSaturationPolicy() {
        return SaturationPolicy.BLOCK_CALLER;
    }
}
```

---

### Queries

Queries are read-only. They never mutate state and are dispatched on a separate bus.

```java
public record GetOrderQuery(String orderId) {}

public class GetOrderQueryHandler
        extends AbstractQueryHandler<GetOrderQuery, String> {

    @Override
    public String handle(GetOrderQuery query) {
        return readModel.findSummaryById(query.orderId());
    }
}

String summary = runtime.queries().ask(
    Query.<GetOrderQuery>builder().body(new GetOrderQuery("order-001")).build());
```

---

### Aggregates and Domain Events

Aggregates own their invariants. They produce domain events — facts about what happened — instead of reaching into external state directly. The runtime drains the aggregate's uncommitted events after every handler invocation and routes them to every registered listener.

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
        recordEvent(new OrderCancelledEvent(getAggregateId(), reason));
    }

    @Override
    protected void apply(DomainEvent event) {
        switch (event) {
            case OrderPlacedEvent    e -> status = "PLACED";
            case OrderCancelledEvent e -> status = "CANCELLED";
            default -> {}
        }
    }
}

public class OrderPlacedEvent extends AbstractDomainEvent {
    private final String productId;
    private final int    quantity;

    public OrderPlacedEvent(String orderId, String productId, int quantity) {
        super(orderId);
        this.productId = productId;
        this.quantity  = quantity;
    }

    public String getProductId() { return productId; }
    public int    getQuantity()  { return quantity; }
}
```

Every `AbstractDomainEvent` carries:
- a monotonically increasing **sequence number** per aggregate instance (starts at 0)
- a default `idempotencyKey()` of `aggregateId + ":" + sequenceNumber`
- `UUID getId()`, `Instant getTimestamp()`, `String getAggregateId()`, `String eventType()`

---

### Event Listeners

```java
public class OrderAuditListener
        extends AbstractDomainEventListener<OrderPlacedEvent> {

    @Override
    public void handle(OrderPlacedEvent event) {
        auditLog.record(event.getAggregateId(), event.getProductId(), event.getQuantity());
    }

    @Override public int order() { return 1; }  // lower = runs first
}
```

**Fan-out rules:**
- Sequential by default; listeners fire in ascending `order()` value, ties broken by registration order.
- Opt-in parallel: override `parallelSafe() → true` on **every** listener for a concrete event class, and set `FlowRuntime.builder().parallelListeners(true)`. Both preconditions must hold.

---

## Advanced Features

### DispatchResult and ErrorPolicy

Every typed dispatch returns a sealed `DispatchResult<T>`. Handle it exhaustively with pattern matching:

```java
DispatchResult<CancellationReceipt> result = runtime.commands().dispatchAndReturnResult(
    cmd, ExecutionContext.root(), ErrorPolicy.collectFailures());

switch (result) {
    case DispatchResult.Success<CancellationReceipt> s ->
        System.out.println("Done: " + s.value());
    case DispatchResult.Failure<?>        f ->
        System.err.println("Failed: " + f.cause().getMessage());
    case DispatchResult.PartialFailure<?> p ->
        p.failures().forEach(e -> System.err.println("Partial: " + e.getMessage()));
    case DispatchResult.Accepted<?>       a ->
        System.out.println("Durably queued, message=" + a.messageId());
}
```

| `ErrorPolicy` | Behaviour |
|---|---|
| `failFast()` | Abort on the first failure; cancel sibling work. |
| `collectFailures()` | Continue all handlers; aggregate all failures into `PartialFailure`. |
| `ignoreFailures(predicate)` | Selectively suppress matching exceptions. |
| `isolate(inner)` | Failures in event listeners do not propagate to the calling command. |

---

### ExecutionContext, Deadlines and Cancellation

`ExecutionContext` is an immutable record that carries trace and correlation IDs, deadlines, and a cooperative `CancellationToken` through the entire call chain. Every child command and event inherits from the parent; deadlines never extend.

```java
ExecutionContext ctx = ExecutionContext.root()
    .withDeadline(Instant.now().plus(Duration.ofSeconds(5)))
    .withCorrelationId(correlationId);

DispatchResult<?> result = runtime.commands()
    .dispatchAndReturnResult(cmd, ctx, ErrorPolicy.failFast());
```

Framework workers (`OutboxWorker`, `ScheduledCommandWorker`, `SagaRunner`) own their own `CancellationToken` for their lifetime. The token is cancelled during `shutdown()`, before `thread.interrupt()`, so in-flight work receives cooperative cancellation signals rather than abrupt interruption.

---

### Transactional Outbox and At-Least-Once Delivery

The **transactional outbox** is the correct pattern for publishing events durably without a distributed transaction. Nexus Flow ships a production-ready `OutboxWorker` with configurable retry backoff, dead-letter routing, and a visibility-timeout sweep for stuck `IN_FLIGHT` rows.

```java
OutboxConfig config = OutboxConfig.builder(
        new InMemoryOutboxStorage(),          // swap for JdbcOutboxStorage in production
        new JavaSerializationOutboxPayloadCodec())
    .workerPollInterval(Duration.ofMillis(50))
    .workerMaxAttempts(5)
    .workerBackoffBase(Duration.ofMillis(20))
    .workerBackoffMax(Duration.ofSeconds(30))
    .staleClaimVisibilityTimeout(Duration.ofMinutes(5))
    .drainOnShutdown(true)
    .deadLetterHandler((row, cause) ->
        deadLetterLog.record(row.outboxId(), row.payloadType(), cause))
    .build();

FlowRuntime runtime = FlowRuntime.builder()
    .outbox(config)
    .handlers(/* ... */)
    .build();
```

**Status lifecycle:**

```
PENDING → IN_FLIGHT → PUBLISHED
                    → FAILED         (retryable, exponential backoff + jitter)
                    → FAILED_TERMINAL (maxAttempts exhausted → DeadLetterHandler)
```

The worker never moves a row backward in the state machine. Decode failures and encode failures on the bridge path are classified as transient (retried), not terminal — preserving at-least-once semantics.

---

### Inbox Deduplication

Pair the outbox with an `InboxStorage` to guarantee exactly-once-effective delivery at the listener side:

```java
OutboxConfig config = OutboxConfig.builder(outboxStorage, codec)
    .inbox(new InMemoryInboxStorage())   // deduplicates by (messageId, consumerId)
    .build();
```

The inbox claims each message by `(messageId, consumerId)` before the worker dispatches. If the claim fails (already processed), the row is marked published and skipped without invoking any listener.

---

### Event Sourcing

```java
EventStore    store     = new InMemoryEventStore();
SnapshotStore snapshots = new InMemorySnapshotStore();

AggregateRepository<OrderAggregate> repo = AggregateRepository
    .builder(store, OrderAggregate.class, OrderAggregate::new)
    .snapshotStore(snapshots)
    .snapshotEvery(50)    // auto-snapshot after every 50 events
    .build();

// Save — appends events; throws OptimisticConcurrencyException on version conflict
repo.save(order);

// Load — hydrates from the latest snapshot + tail events
OrderAggregate loaded = repo.load(orderId);
```

Projections subscribe to the global event stream position log for catch-up and live updates. `StreamId` is a typed, versioned cursor; `AppendResult` carries the accepted version for optimistic-lock validation.

---

### Sagas and Process Managers

Long-running multi-step processes are `Saga<K>` implementations. The `SagaRunner` drives catch-up from the event store, persists checkpoint state under optimistic concurrency, and routes compensation commands through the outbox.

```java
public class OrderFulfillmentSaga implements Saga<String> {

    @Override
    public String correlate(DomainEvent event) {
        return switch (event) {
            case OrderPlacedEvent     e -> e.getAggregateId();
            case PaymentConfirmedEvent e -> e.getAggregateId();
            default -> null;
        };
    }

    @Override
    public SagaTransition handle(SagaState<String> state, DomainEvent event) {
        return switch (event) {
            case OrderPlacedEvent     e ->
                SagaTransition.advance(new ConfirmPaymentCommand(e.getAggregateId()));
            case PaymentConfirmedEvent e ->
                SagaTransition.complete();
            default -> SagaTransition.skip();
        };
    }

    @Override
    public SagaTransition compensate(SagaState<String> state, Throwable cause) {
        return SagaTransition.compensate(
            new RefundPaymentCommand(state.correlationKey()));
    }
}
```

The advanced demo (`project/demo`) runs a full two-scenario saga: happy path (stock reserved → payment charged) and compensation path (stock fails → order cancelled via `CompensationService`).

---

### Observability

**JFR — zero-overhead production tracing:**

Three built-in JFR events fire on every dispatch (zero-cost when no recording is active):

| JFR Event | Key fields |
|---|---|
| `net.nexusflow.CommandDispatch` | `commandType`, `outcome`, `failureClass` |
| `net.nexusflow.EventPublish` | `eventType`, `listenerCount`, `parallelFanOut`, `outcome` |
| `net.nexusflow.HandlerInvoke` | `targetType`, `handlerType`, `success`, `failureClass` |

Filter on JFR category `NexusFlow/CQRS` in Mission Control or `jfr print`.

**Dispatch interceptors — custom logging, tracing, or metrics:**

```java
FlowRuntime.builder()
    .interceptor(new LoggingDispatchInterceptor())
    .interceptor(openTelemetryInterceptor)
    .build();
```

Each interceptor receives an `InvocationContext` with the `ExecutionContext`, the message type, and the `InvocationStage` (before/after invocation). The chain is ordered by registration; interceptors compose without framework involvement.

**SPI seats** — `MetricsRecorder` and `TracingBridge` are pluggable interfaces. Micrometer and OpenTelemetry adapter modules are planned (see roadmap below) and will implement these interfaces without changing `core`.

---

## Distributed Ring Transport

For multi-pod event fan-out without an external broker, Nexus Flow ships a **ring transport** layer: a peer-to-peer overlay of persistent mTLS socket channels over which domain events and commands route via consistent hashing.

```
Pod A ──────────── mTLS ──────────► Pod B
  │                                    │
  └──────── RingRuntime ───────────────┘
       membership  · heartbeat
       frame codec · dispatch routing
       event fan-out bridges
```

**Wiring:**

```java
RingRuntime ring = RingRuntime.builder(PeerId.of("pod-a"))
    .seedPeers(Map.of(
        PeerId.of("pod-b"), new PeerAddress("pod-b.svc", 7700)))
    .acceptorConfig(RingAcceptorConfig.bound("0.0.0.0", 7700))
    .certificateSource(CertificateSource.ofStaticConfig(tlsConfig))
    .attachTo(flowRuntime)
    .enableLiveFanOut(new JavaSerializationOutboxPayloadCodec(), "java-v1")
    .enableLocalDispatch(new JavaSerializationCommandPayloadCodec())
    .inboxStorage(new InMemoryInboxStorage())
    .sagaStorage(sagaStorage)
    .faultLimits(RouterFaultLimits.DEFAULTS)
    .build();

ring.start();
```

**Live fan-out** — `RingEventBusBridge` intercepts every event published on the local bus and serializes it to every alive remote peer via the persistent ring connection. The matching `EventBusRingInboundHandler` re-publishes inbound EVENT frames on the receiver's local bus and (when an `InboxStorage` is wired) deduplicates against the durable path so live + durable duplicates collapse to a single dispatch.

**Durable fan-out** — `RingOutboxBridge` reads outbox rows (owned by the ring via `OutboxOwnership.RING_BRIDGE_ONLY` or `RING_BRIDGE_WITH_WORKER_FAILOVER`) and fans them out to remote pods. The failover ownership mode implements a formal **pause/resume handshake**: the bridge pauses the `OutboxWorker` at `start()` and resumes it at `close()`, so there is never a window where both the worker and the bridge compete to claim the same rows.

**Cross-pod commands** — `RingCommandFallback` (sender side) routes commands whose handler lives on a different pod through `RingDispatcher`; the receiver's `RuntimeBackedLocalDispatchHandler` decodes the payload, resolves it through an optional class-allowlist, and routes to the local `CommandBus` / `QueryBus`. Return-handlers map their `DispatchResult` to the wire response; fire-and-forget handlers surface as `ACCEPTED`.

**Saga ownership** — when a `SagaStorage` is wired, the `SagaLeaseCoordinator` performs ownership claims through `StorageBackedSagaOwnershipClaimer`, which delegates to `SagaStorage.tryAcquireOwnershipById` for an atomic compare-and-set against durable storage. Without the storage hook the in-tree stub grants every claim — fine for single-node tests, fatal for multi-pod production.

**Peer routing** — three selectors ship in-tree: `RoundRobinPeerSelector` (fair distribution), `HashRingPeerSelector` (consistent-hash aggregate affinity), and `LocalFirstPeerSelector` (prefers the local pod when it advertises the handler, skipping the network hop entirely).

**Connection eviction** — `RingFrameRouter` tracks per-peer fault rate in a rolling window (configurable via `RouterFaultLimits`, default `10 faults / 60 s`); peers that cross the threshold are disconnected, eliminating malformed-frame and panic-handler loops without an operator round-trip.

**Operations facade:**

```java
RingOps ops = ring.ops();

RingHealthStatus health = ops.health();
// → { localPeer, membershipSize, connectedPeers, acceptorLive, pendingDispatches }

ops.quiesce(Duration.ofSeconds(10));  // blocks until in-flight drops to zero
ops.drainOutbox();                    // returns count of rows fanned out in this sweep
```

**Hot-rotating mTLS certificates** — `CertificateSource` is an SPI for cert-manager, Vault, and SPIFFE/SPIRE adapters:

```java
CertificateSource source = certManagerAdapter.watch("/var/run/secrets/tls");
source.subscribe(newConfig -> ring.rotateTls(newConfig));

RingRuntime ring = RingRuntime.builder()
    .tls(source)
    // ...
    .build();
```

---

## Demos

### Basic demo — command / query / event in one process

```bash
./gradlew :project:demo:runDemo
```

Exercises `PlaceOrderCommand`, `CancelOrderCommand`, `GetOrderQuery`, and two event listeners through a standard `FlowRuntime`. Shows the `DispatchResult` ADT and typed return values.

### Advanced demo — outbox + saga + dead-letter + audit trail

```bash
./gradlew :project:demo:runAdvancedDemo
```

Runs two end-to-end scenarios through the full durable-async stack:

| Scenario | Flow |
|---|---|
| **Happy path** (qty=3) | `PlaceOrderCommand` → `OrderPlacedEvent` → outbox → `StockReservedEvent` → `PaymentChargedEvent` |
| **Compensation** (qty=999) | `PlaceOrderCommand` → `OrderPlacedEvent` → outbox → `StockReservationFailedEvent` → `OrderCancelledEvent` |

Dead-letter handler, audit-trail listener, and `OutboxWorker` with exponential backoff and `drainOnShutdown` are all live.

---

## Performance

Baselines captured on **Windows / Temurin-25.0.2+10-LTS** with JMH defaults (3 warmup, 3 measurement, 1 fork, 1 s/iter):

| Benchmark | Params | Score | Unit |
|---|---|---:|---|
| `DispatchPlanLookupBenchmark.classValueCachedLookup` | 1 type | **3.6** | ns/op |
| `DispatchPlanLookupBenchmark.classValueCachedLookup` | 100 types | **3.6** | ns/op |
| `EventFanOutBenchmark.dispatchResult_failFast` | 1 listener | **1.25** | µs/op |
| `EventFanOutBenchmark.dispatchResult_failFast` | 10 listeners | **4.9** | µs/op |
| `EventFanOutBenchmark.dispatchResult_failFast` | 100 listeners | **41.8** | µs/op |
| `EventFanOutBenchmark.dispatch_legacyFireAndForget` | 1 listener | **11** | ns/op |
| `TypeReferenceHashBenchmark.hashCode_cached` | — | **0.6** | ns/op |
| `SpscQueueBenchmark.spscOfferPoll` | offer + poll round-trip | **4.9** | ns/op |
| `SpscQueueBenchmark.linkedBlockingQueueOfferPoll` | offer + poll round-trip | **55.2** | ns/op |
| `HotPathOptimizationBenchmark.cancelExceptionAllocation` | stack-traceless | **4.1** | ns/op |
| `HotPathOptimizationBenchmark.dispatchResultSuccessSingleton` | cached singleton | **0.2** | ns/op |
| `ErrorClassificationBenchmark.classifyTechnical` | verbatim pass-through | **1.4** | ns/op |
| `ErrorClassificationBenchmark.classifyPlainRuntimeWrapping` | stack-traceless Technical wrap | **6** | ns/op |

`ClassValue` dispatch-plan lookup is **O(1) across any number of registered types** — the benchmark shows identical latency at 1, 10, and 100 registered command types. Event fan-out scales linearly with listener count as expected for sequential delivery.

### Key perf primitives

- **`FastUuid.v4`** — UUID v4 from `ThreadLocalRandom`, wire-format-identical to `UUID.randomUUID()` but ~10× faster. Used for every observability handle (`MessageId`, `TraceId`, `CorrelationId`, …). Privacy-boundary IDs (`AggregateRoot.getAggregateId`) keep `SecureRandom` deliberately.
- **Stack-traceless exception singletons** — `FlowCancellationException.CANCELLED`, `DispatchResult.SUCCESS_VOID`, `FlowError.Technical` cause-wrappers all skip `fillInStackTrace`. Cancel/failure allocation drops from ~500 ns to ~4 ns (124× faster).
- **`SpscRingBuffer`** — wait-free lock-free SPSC queue with padded cursors + `VarHandle` ops. Offer + poll round-trip in ~5 ns (vs `LinkedBlockingQueue` at ~55 ns); used wherever the producer/consumer cardinality is statically 1.
- **`ClassNameCache`** — process-wide `ClassValue<String>` cache for `Class.getName()` calls fired by JFR field assignment. Amortises class-metadata walks to a single field read after the JIT warms.
- **`ClassValue`-cached `DispatchPlan`** — handler lookup is a single weakly-cached `ClassValue.get()` per dispatched message type. Adding handlers does not change lookup latency.

Run benchmarks locally:

```bash
./gradlew :project:benchmarks:jmh
# Detailed: 5 warmup + 10 measurement + 2 forks + GC pressure profiler
./gradlew :project:benchmarks:jmh --args="-wi 5 -i 10 -f 2 -r 5s -prof gc"
```

---

## Adapter Modules (Roadmap)

These modules are design-reserved and will extend `core` without modifying it:

| Module | What it provides |
|---|---|
| `nexus-flow-jdbc` | `JdbcOutboxStorage`, `JdbcInboxStorage`, `JdbcSagaStorage`, `JdbcEventStore`; dialect strategies for Postgres (`FOR UPDATE SKIP LOCKED`), MySQL, H2, SQL Server; Flyway migrations; Testcontainers smoke tests |
| `nexus-flow-otel` | `Observability` backed by the OpenTelemetry SDK (Tracer + Meter); OTLP exporter wiring left to the host process |
| `nexus-flow-micrometer` | `MetricsRecorder` backed by Micrometer `MeterRegistry`; Spring Boot Actuator-compatible |
| `nexus-flow-resilience4j` | `QueryCircuitBreaker` and `RetryPolicy` adapters |
| `nexus-flow-kafka` | Kafka dead-letter queue, integration-event publisher, optional log-compacted `KafkaEventStore` |
| `nexus-flow-rabbit` | RabbitMQ DLQ and integration-event publisher |
| `nexus-flow-redis-lettuce` | Distributed `EventDeduplicator`, `TokenBucket` (Bucket4j-on-Lettuce), Redis-resident `SagaStorage` |
| `nexus-flow-debezium` | CDC-driven `OutboxStorage` reader — turns the outbox into a Kafka source via Debezium's outbox event router SMT |
| `nexus-flow-spring` | IoC integration via annotation scanning; `@CommandHandler` / `@EventListener` / `@QueryHandler` component detection |
| `nexus-flow-quarkus` | Build-time index via Jandex; Arc bean integration |
| `nexus-flow-micronaut` | AST-time processing; Micronaut DI integration |

Any code added to `core` must be implementable by at least one of these modules without re-opening `core`.

---

## Build and Quality Gates

```bash
# Full build
./gradlew build

# Core test suite
./gradlew :project:core:test

# Specific test
./gradlew :project:core:test --tests "net.nexus_flow.core.outbox.OutboxWorkerTest"

# Auto-format
./gradlew :project:core:spotlessApply

# Static analysis
./gradlew :project:core:pmdMain :project:core:pmdTest :project:core:spotbugsMain

# Javadoc
./gradlew :project:core:javadoc

# Benchmarks
./gradlew :project:benchmarks:jmh
```

Requires **JDK 25** with `--enable-preview`. The toolchain is configured in the root `build.gradle.kts`; no per-task overrides are needed.

---

## Contributing

1. Fork the repository and create a feature or bugfix branch.
2. Respect the layering rules in `package-info.java` (every package documents what belongs there and what does not): public contracts in `api`, pure logic in `core`, backends in `runtime`, capability detection in `platform`.
3. Run `./gradlew :project:core:spotlessApply` before committing; the CI check (`spotlessCheck`) will fail on unformatted files.
4. Every non-trivial change ships with tests: unit, contract (cross-backend), and concurrency tests as applicable.
5. Submit a pull request to `main`. Reference the relevant architectural invariant in the PR description.

See [CONTRIBUTING.adoc](CONTRIBUTING.adoc) for the full process, code of conduct, and security disclosure policy.

Issues and feature suggestions: [open an issue](https://github.com/Nexus-Flow/nexus-flow/issues).

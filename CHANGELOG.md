# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.1-SNAPSHOT]

### Added

- `FlowRuntime` as the application entry point with builder-driven lifecycle (`build → live → close`) and idempotent shutdown.
- Command, query and event buses with sealed `DispatchResult<R>` / `CommandResult<R>` hierarchies for explicit success/failure handling.
- Pluggable execution strategies: inline, asynchronous in-memory, asynchronous durable; selected per-handler via `CommandSettings#executionMode()`.
- Virtual-thread carriers with deadline and cancellation propagation through `ExecutionContext` (trace id, correlation id, causation id, attributes).
- Handler back-pressure with bounded per-handler queues, configurable saturation policies, and surfaced `SaturationRejectedException` as `FlowError.Technical`.
- `DispatchInterceptor` chain with stage-attributed error wrapping (`PRE`/`POST`); domain errors propagate verbatim, technical errors are wrapped with the failing context.
- `HandlerRegistry` with `ClassValue`-cached dispatch plans and `MethodHandle`-backed invokers; sequential fan-out in registration order with stable tie-breaking.
- DDD building blocks: `AbstractAggregate`, `AbstractDomainEvent`, aggregate-owned event recording, and a JVM-wide `DomainEventContext` bridge.
- Event sourcing: `EventStore`, `AggregateRepository`, snapshot SPI, projections.
- Transactional outbox / inbox SPIs with in-memory implementations and a kill-switch for synchronous publication.
- Sagas and process managers with cooperative cancellation and checkpoint-based replay.
- Observability hooks: JFR events, `System.Logger` integration, `TracingBridge` SPI, `Clock` SPI for deterministic tests.
- `ScheduledCommandWorker` with deduplicated `scheduleId`, exponential backoff and exhaustion semantics.
- Graceful shutdown contract with drain-then-terminate semantics for in-flight dispatches.
- Demo module (`net.nexus_flow.NexusFlowDemo`) showcasing command/query/event flow over an `OrderAggregate`.
- JMH benchmarks for dispatch-plan lookup, `TypeReference` hashing and event fan-out.
- Tooling: Spotless (google-java-format), PMD, SpotBugs, JUnit Jupiter, Maven publication of `nexusflow-core` and its tests jar.

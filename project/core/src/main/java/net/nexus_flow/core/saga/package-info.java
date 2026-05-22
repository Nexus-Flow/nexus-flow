/**
 * Saga / process-manager pattern for long-running, multi-step business transactions that span
 * multiple aggregates or services.
 *
 * <h2>Saga vs. plain event handler</h2>
 *
 * <p>A plain event handler (projection, read-model updater) is stateless: it transforms an event
 * into a side effect and forgets it. A saga is <em>stateful</em>: it accumulates state across many
 * events, makes decisions based on that accumulated state, and can emit compensating actions when
 * earlier steps must be rolled back. {@link net.nexus_flow.core.saga.Saga} is the behavioral
 * contract; {@link net.nexus_flow.core.saga.SagaState} is the durable snapshot that makes the saga
 * restartable.
 *
 * <h2>Saga vs. process manager</h2>
 *
 * <p>The terms are used interchangeably here. Formally, a <em>saga</em> is a choreography-based
 * sequence of local transactions, each publishing an event that triggers the next step; a
 * <em>process manager</em> (orchestrator saga) additionally maintains an explicit routing table and
 * may command aggregates directly. Both patterns share the same {@link
 * net.nexus_flow.core.saga.Saga} contract in this framework.
 *
 * <h2>Compensation model</h2>
 *
 * <p>When a step fails the saga returns {@link net.nexus_flow.core.saga.SagaTransition.Compensate},
 * which carries a non-empty list of compensating domain events. The runner routes those events
 * through the outbox so they are delivered durably even if the process restarts before dispatch.
 * The saga enters {@link net.nexus_flow.core.saga.SagaStatus#COMPENSATED} in a single step
 * (one-phase compensation, not multi-round).
 *
 * <h3>At-least-once delivery and idempotency</h3>
 *
 * <p>Because the outbox guarantees at-least-once delivery, compensation consumers MUST be
 * idempotent. Downstream aggregates must guard with optimistic concurrency or a deduplication inbox
 * to prevent double-application of compensation events.
 *
 * <h3>Compensation failure handling</h3>
 *
 * <p>If the outbox append itself fails (storage I/O error), the exception propagates to the caller
 * — the runner does NOT silently swallow compensation failures. This ensures failure visibility and
 * allows for proper error handling and observability.
 *
 * <h2>Concurrency model and single-leader safety</h2>
 *
 * <p>{@link net.nexus_flow.core.saga.SagaRunner} is designed for single-leader execution: at most
 * one runner instance should process a given saga type concurrently. The storage layer enforces
 * optimistic concurrency via version checking as a safety net — concurrent writes to the same saga
 * instance will cause {@link net.nexus_flow.core.saga.SagaConcurrencyException} on the loser. Saga
 * state machine transitions are atomic at the storage layer; the per-saga checkpoint ({@link
 * net.nexus_flow.core.saga.SagaState#lastProcessedGlobalPosition()}) ensures restart safety and
 * idempotency.
 */
@NullMarked
package net.nexus_flow.core.saga;

import org.jspecify.annotations.NullMarked;

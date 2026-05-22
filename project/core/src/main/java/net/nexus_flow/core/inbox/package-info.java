/**
 * Inbox deduplication tier: converts at-least-once delivery into exactly-once processing.
 *
 * <h2>Role in the CQRS/ES pipeline</h2>
 *
 * <p>A message broker (Kafka, RabbitMQ, SQS, …) guarantees <em>at-least-once</em> delivery: a
 * consumer may receive the same message more than once due to retries, partition rebalances, or
 * crash recovery. This package provides the primitives that turn those duplicate deliveries into a
 * single successful processing attempt per consumer pipeline.
 *
 * <p>The deduplication key is the {@code (messageId, consumerId)} pair. {@link
 * net.nexus_flow.core.inbox.InboxStorage#claimIfNew} is the atomic gate: exactly one call per pair
 * returns {@link net.nexus_flow.core.inbox.InboxClaim.Fresh}; every subsequent call returns {@link
 * net.nexus_flow.core.inbox.InboxClaim.Duplicate}.
 *
 * <h2>Inbox vs Outbox vs Dead-Letter</h2>
 *
 * <ul>
 * <li><strong>Outbox</strong> — producer side. Domain events are written transactionally
 * alongside the aggregate state change, then relayed to the broker. Guarantees at-least-once
 * <em>publication</em>.
 * <li><strong>Inbox</strong> (this package) — consumer side. Tracks which messages have been
 * successfully processed so duplicate deliveries are silently dropped. Combined with the
 * broker's at-least-once guarantee, this achieves exactly-once <em>processing</em> semantics.
 * <li><strong>Dead-Letter</strong> — failure quarantine. Messages that exhaust all retry attempts
 * transition to {@link net.nexus_flow.core.inbox.InboxStatus#FAILED} and may be moved to a
 * dead-letter store for manual inspection and replay. The inbox layer surfaces this signal;
 * the retry and dead-letter policies belong to the adapter or application layer.
 * </ul>
 *
 * <h2>Canonical surfaces</h2>
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.inbox.InboxStorage} — SPI implemented by persistence adapters
 * (JDBC, Redis, …).
 * <li>{@link net.nexus_flow.core.inbox.InboxClaim} — sealed result type returned by {@code
 *       claimIfNew}; pattern-match on {@code Fresh} vs {@code Duplicate} to drive processing logic.
 * <li>{@link net.nexus_flow.core.inbox.InboxRecord} — immutable, persistent row representation
 * carrying status, timestamps, and an optional error message.
 * <li>{@link net.nexus_flow.core.inbox.InMemoryInboxStorage} — thread-safe in-memory
 * implementation for tests and demos; not suitable for multi-replica deployments.
 * </ul>
 *
 * <h2>Threading model</h2>
 *
 * <p>All {@link net.nexus_flow.core.inbox.InboxStorage} operations must be thread-safe. Concurrent
 * calls to {@code claimIfNew} with the same {@code (messageId, consumerId)} pair must produce
 * exactly one {@link net.nexus_flow.core.inbox.InboxClaim.Fresh} result — this is the core
 * exactly-once invariant.
 *
 * <p>The in-memory implementation uses {@link java.util.concurrent.ConcurrentHashMap#putIfAbsent}
 * as the atomic gate. JDBC/Redis adapters must use database-level {@code INSERT … ON CONFLICT DO
 * NOTHING} (or equivalent) paired with a {@code UNIQUE} constraint on {@code (message_id,
 * consumer_id)}, or a Redis {@code SET … NX} with an appropriate TTL.
 *
 * <h2>Extension points</h2>
 *
 * <p>Implement {@link net.nexus_flow.core.inbox.InboxStorage} to provide a persistent backend. No
 * framework-specific or infrastructure code belongs in this package; adapters live in separate
 * modules (e.g. {@code nexus-flow-jdbc}, {@code nexus-flow-redis}).
 */
@NullMarked
package net.nexus_flow.core.inbox;

import org.jspecify.annotations.NullMarked;

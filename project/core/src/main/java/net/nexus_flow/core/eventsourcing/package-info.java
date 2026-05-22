/**
 * Event-sourcing substrate for the Nexus Flow framework.
 *
 * <h2>Role</h2>
 *
 * This package provides the canonical surfaces for storing, replaying, and projecting {@link
 * net.nexus_flow.core.ddd.DomainEvent}s:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.eventsourcing.EventStore} — append-only log partitioned by
 * {@link net.nexus_flow.core.eventsourcing.StreamId}; the single source of truth.
 * <li>{@link net.nexus_flow.core.eventsourcing.AggregateRepository} — loads and saves {@link
 * net.nexus_flow.core.ddd.Aggregate} instances by replaying their event streams and
 * optionally short-circuiting replay via {@link
 * net.nexus_flow.core.eventsourcing.SnapshotStore}.
 * <li>{@link net.nexus_flow.core.eventsourcing.ProjectionRunner} — drives a {@link
 * net.nexus_flow.core.eventsourcing.Projection} against the global envelope sequence,
 * persisting progress to a {@link
 * net.nexus_flow.core.eventsourcing.ProjectionCheckpointStore}.
 * </ul>
 *
 * <h2>Threading model</h2>
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.eventsourcing.InMemoryEventStore} uses per-stream {@link
 * java.util.concurrent.locks.ReentrantLock}s plus a global lock for the envelope log;
 * cross-stream appends do not serialise on each other.
 * <li>{@link net.nexus_flow.core.eventsourcing.AggregateRepository} is stateless and safe to
 * share across threads; each call operates on an isolated aggregate instance.
 * <li>{@link net.nexus_flow.core.eventsourcing.ProjectionRunner} confines all {@link
 * net.nexus_flow.core.eventsourcing.Projection#apply} calls to a single daemon thread.
 * </ul>
 *
 * <h2>Extension points</h2>
 *
 * Provide your own {@link net.nexus_flow.core.eventsourcing.EventStore}, {@link
 * net.nexus_flow.core.eventsourcing.SnapshotStore}, or {@link
 * net.nexus_flow.core.eventsourcing.ProjectionCheckpointStore} implementations (e.g., JDBC-backed)
 * without changing application code.
 */
@NullMarked
package net.nexus_flow.core.eventsourcing;

import org.jspecify.annotations.NullMarked;

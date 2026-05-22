/**
 * Scheduled-command subsystem for the Nexus Flow runtime.
 *
 * <h2>Concept</h2>
 *
 * <p>A <em>scheduled command</em> is a {@link net.nexus_flow.core.cqrs.command.Command} that must
 * be dispatched through the {@link net.nexus_flow.core.cqrs.command.CommandBus} at or after a
 * designated instant ({@code fireAt}). This is distinct from a cron job (which fires on a recurring
 * schedule) — each scheduled command has a unique {@link
 * net.nexus_flow.core.scheduling.ScheduledCommandId} and fires exactly once.
 *
 * <h2>Delivery guarantees</h2>
 *
 * <p>The subsystem targets <em>at-most-once</em> dispatch in the in-memory backend and
 * <em>at-least-once</em> with idempotent handlers once a JDBC backend is wired in. Callers are
 * expected to assign a stable {@link net.nexus_flow.core.scheduling.ScheduledCommandId} (e.g.
 * derived from a domain event id) so that re-scheduling a command that was already dispatched is a
 * no-op — the storage layer rejects duplicates via {@link
 * net.nexus_flow.core.scheduling.ScheduledCommandDuplicateException}.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Every row transitions through the states defined by {@link
 * net.nexus_flow.core.scheduling.ScheduledCommandStatus}:
 *
 * <pre>
 * PENDING ──dispatch ok──&gt; DISPATCHED (terminal, happy path)
 * PENDING ──transient err─&gt; PENDING (rescheduled with backoff)
 * PENDING ──terminal err──&gt; FAILED_TERMINAL (terminal, manual inspection)
 * </pre>
 *
 * <h2>Polling and drift handling</h2>
 *
 * <p>{@link net.nexus_flow.core.scheduling.ScheduledCommandWorker} polls {@link
 * net.nexus_flow.core.scheduling.ScheduledCommandStorage#claimDue(int, java.time.Instant)} on a
 * configurable interval (default: 200 ms). Commands are fired as soon as the poll cycle observes
 * {@code fireAt &lt;= now}; slight positive drift (up to one {@code pollInterval}) is expected and
 * acceptable. The injected {@link java.time.Clock} MUST be pure; caching {@code Instant.now()}
 * across ticks will cause missed deadlines.
 *
 * <h2>Retry and terminal failure</h2>
 *
 * <p>On a transient dispatch failure the worker reschedules the row with exponential backoff capped
 * at {@link net.nexus_flow.core.scheduling.ScheduledCommandConfig#backoffMax()}. After {@link
 * net.nexus_flow.core.scheduling.ScheduledCommandConfig#maxAttempts()} the row transitions to
 * {@link net.nexus_flow.core.scheduling.ScheduledCommandStatus#FAILED_TERMINAL} for manual
 * inspection or tooling.
 *
 * <h2>Concurrency</h2>
 *
 * <p>The {@link net.nexus_flow.core.scheduling.ScheduledCommandWorker} runs on a single dedicated
 * daemon thread. The {@link net.nexus_flow.core.scheduling.ScheduledCommandStorage} interface is
 * called exclusively from this worker thread for polling and status updates, except for {@link
 * net.nexus_flow.core.scheduling.ScheduledCommandStorage#schedule(net.nexus_flow.core.scheduling.ScheduledCommandRecord)},
 * which may be called from arbitrary threads. Implementations must be thread-safe for concurrent
 * access from both contexts.
 *
 * <h2>Key types</h2>
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.scheduling.ScheduledCommandRecord} — durable row
 * <li>{@link net.nexus_flow.core.scheduling.ScheduledCommandStorage} — storage contract
 * <li>{@link net.nexus_flow.core.scheduling.InMemoryScheduledCommandStorage} — test/demo backend
 * <li>{@link net.nexus_flow.core.scheduling.ScheduledCommandWorker} — polling daemon
 * <li>{@link net.nexus_flow.core.scheduling.ScheduledCommandConfig} — configuration
 * </ul>
 */
@NullMarked
package net.nexus_flow.core.scheduling;

import org.jspecify.annotations.NullMarked;

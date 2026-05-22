package net.nexus_flow.core.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable storage contract for {@link ScheduledCommandRecord}s.
 *
 * <p>The contract mirrors {@code OutboxStorage} but is specialized for the scheduled-command
 * subsystem. Implementations:
 *
 * <ul>
 * <li>{@link InMemoryScheduledCommandStorage} — for tests and single-node demos.
 * <li>JDBC variant — deferred to a future release; will use {@code SELECT FOR UPDATE SKIP LOCKED}
 * to support multi-node deployments with exactly-once dispatch.
 * </ul>
 *
 * <p><strong>Thread-safety:</strong> implementations MUST be safe for concurrent calls from the
 * worker thread (which calls {@link #claimDue(int, Instant)}, {@link #markDispatched}, {@link
 * #rescheduleAfterFailure}, {@link #markFailedTerminal}) and arbitrary caller threads (which call
 * {@link #schedule(ScheduledCommandRecord)} and {@link #find(ScheduledCommandId)}).
 *
 * <p><strong>Semantics:</strong>
 *
 * <ul>
 * <li>{@code schedule()} atomically inserts a new row or throws {@link
 * ScheduledCommandDuplicateException} if a non-terminal row with the same id already exists.
 * <li>{@code claimDue()} returns rows eligible for dispatch (status=PENDING and {@code fireAt
 *       &lt;= now}) and may apply a soft lock to prevent concurrent workers from claiming the same
 * row.
 * <li>Status transitions are atomic per-row; the same row cannot be updated by two concurrent
 * calls.
 * </ul>
 */
public interface ScheduledCommandStorage {

    /**
     * Persist a fresh {@link ScheduledCommandStatus#PENDING} row.
     *
     * <p>If a row with the same {@link ScheduledCommandId} already exists in a non-terminal status
     * ({@link ScheduledCommandStatus#PENDING}), the call throws {@link
     * ScheduledCommandDuplicateException}. Rows in a terminal status ({@link
     * ScheduledCommandStatus#DISPATCHED} or {@link ScheduledCommandStatus#FAILED_TERMINAL}) may be
     * replaced, enabling callers to re-queue a previously completed or failed command.
     *
     * <p><strong>Idempotent scheduling:</strong> callers can derive the record id deterministically
     * from a domain event (e.g., via a hash of the event id) and call this method multiple times
     * safely. The second and subsequent calls will raise {@link ScheduledCommandDuplicateException},
     * signalling that the command is already scheduled. The command will still be dispatched by the
     * existing row, making the operation logically idempotent.
     *
     * @param record a PENDING record to persist; must not be {@code null}
     * @throws ScheduledCommandDuplicateException if a non-terminal row with the same id already
     *                                            exists
     * @throws IllegalArgumentException           if {@code record.status()} is not {@link
     *                                            ScheduledCommandStatus#PENDING}
     */
    void schedule(ScheduledCommandRecord record);

    /**
     * Return up to {@code max} rows whose {@code fireAt &lt;= now} and whose status is {@link
     * ScheduledCommandStatus#PENDING}, ordered by {@code fireAt} ascending then by id ascending.
     *
     * <p>Rows are left in {@code PENDING} status after the claim; the worker resolves each row
     * through {@link #markDispatched} or {@link #rescheduleAfterFailure}. Implementations MAY apply a
     * soft lock (e.g. a coarse {@code synchronized} block or a database advisory lock) so that two
     * concurrent workers do not claim the same batch.
     *
     * <p><strong>Polling interval:</strong> the worker calls this method repeatedly at intervals
     * defined by {@link ScheduledCommandConfig#pollInterval()}. Each call uses the fresh instant from
     * the injected {@link java.time.Clock}; slight positive drift (up to one poll interval) is
     * expected and acceptable when a command's {@code fireAt} falls between two polls.
     *
     * @param max maximum number of rows to return; if {@code &lt;= 0} returns an empty list
     * @param now the reference instant for the {@code fireAt} comparison (typically {@code
     *     config.clock().instant()})
     * @return an unmodifiable snapshot of claimed rows; never {@code null}
     */
    List<ScheduledCommandRecord> claimDue(int max, Instant now);

    /**
     * Mark the row as successfully dispatched.
     *
     * <p>Status transitions to {@link ScheduledCommandStatus#DISPATCHED} and {@code attempt} is
     * incremented. This is a terminal state; no further retries will occur.
     *
     * @param id the id of the row to update; must not be {@code null}
     * @param at the wall-clock instant of the successful dispatch; used to update {@code updatedAt}
     */
    void markDispatched(ScheduledCommandId id, Instant at);

    /**
     * Record a transient dispatch failure and reschedule the row.
     *
     * <p>Status stays {@link ScheduledCommandStatus#PENDING}, {@code attempt} is incremented, {@code
     * fireAt} is set to {@code nextFireAt} (after exponential backoff), and {@code lastError} is
     * updated with the error message.
     *
     * @param id         the id of the row to update; must not be {@code null}
     * @param nextFireAt the earliest instant at which to retry the dispatch (typically {@code
     *     now.plus(backoffDuration)})
     * @param error      human-readable description of the failure (e.g., exception message or class name)
     */
    void rescheduleAfterFailure(ScheduledCommandId id, Instant nextFireAt, String error);

    /**
     * Mark the row as permanently failed after exhausting all retry attempts.
     *
     * <p>Status transitions to {@link ScheduledCommandStatus#FAILED_TERMINAL}, {@code attempt} is
     * incremented, and {@code lastError} is updated. The row is left in storage for audit,
     * monitoring, and manual remediation.
     *
     * @param id    the id of the row to update; must not be {@code null}
     * @param error human-readable description of the final failure
     * @param at    the wall-clock instant of the terminal failure; used to update {@code updatedAt}
     */
    void markFailedTerminal(ScheduledCommandId id, String error, Instant at);

    /**
     * Read a single row by id.
     *
     * <p>Optional accessor primarily intended for tests and inspection tools.
     *
     * @param id the id to look up; must not be {@code null}
     * @return an {@link Optional} containing the row, or empty if not found
     */
    Optional<ScheduledCommandRecord> find(ScheduledCommandId id);
}

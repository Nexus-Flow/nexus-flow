package net.nexus_flow.core.scheduling;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link ScheduledCommandStorage} for tests and single-node demos.
 *
 * <p><strong>Not production-grade.</strong> The backing {@link ConcurrentHashMap} is JVM-local;
 * rows do not survive a restart. A future JDBC backend will provide at-least-once delivery across
 * crashes.
 *
 * <p><strong>Class placement:</strong> this class lives in the main source set (not {@code test})
 * so that consumers of the framework can reference it in their own test helpers and integration
 * setups without a test-jar dependency.
 *
 * <p><strong>Concurrency model:</strong>
 *
 * <ul>
 * <li>{@link #schedule(ScheduledCommandRecord)} uses {@link ConcurrentHashMap#compute} for an
 * atomic check-and-insert, throwing {@link ScheduledCommandDuplicateException} only if a
 * non-terminal row already exists.
 * <li>{@link #claimDue(int, Instant)} synchronises on a coarse {@code claimLock} so the snapshot
 * of eligible rows is consistent — mirrors {@code InMemoryOutboxStorage.claimBatch}.
 * <li>All status-transition methods ({@code markDispatched}, {@code rescheduleAfterFailure},
 * {@code markFailedTerminal}) use {@link ConcurrentHashMap#compute} so updates to the same
 * row are serialized against each other and thread-safe.
 * </ul>
 *
 * <p><strong>Fire-at ordering:</strong> {@link #claimDue(int, Instant)} sorts returned rows by
 * {@code fireAt} ascending, then by id ascending, ensuring deterministic processing order and
 * predictable test behavior.
 */
public final class InMemoryScheduledCommandStorage implements ScheduledCommandStorage {

    private final ConcurrentHashMap<ScheduledCommandId, ScheduledCommandRecord> byId      =
            new ConcurrentHashMap<>();
    private final Object                                                        claimLock = new Object();

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@link ConcurrentHashMap#compute} to atomically check for an existing row. Only a
     * {@link ScheduledCommandStatus#PENDING} row blocks the insert; terminal rows ({@link
     * ScheduledCommandStatus#DISPATCHED} or {@link ScheduledCommandStatus#FAILED_TERMINAL}) are
     * replaced, allowing callers to re-queue a previously completed or failed command.
     */
    @Override
    public void schedule(ScheduledCommandRecord commandRecord) {
        Objects.requireNonNull(commandRecord, "commandRecord");
        if (commandRecord.status() != ScheduledCommandStatus.PENDING) {
            throw new IllegalArgumentException(
                    "schedule() requires a PENDING commandRecord but got: " + commandRecord.status());
        }
        final ScheduledCommandDuplicateException[] dup = {null};
        byId.compute(
                     commandRecord.id(),
                     (k, prior) -> {
                         if (prior != null && prior.status() == ScheduledCommandStatus.PENDING) {
                             dup[0] = new ScheduledCommandDuplicateException(commandRecord.id());
                             return prior; // leave the existing row untouched
                         }
                         return commandRecord; // insert or replace terminal row
                     });
        if (dup[0] != null) {
            throw dup[0];
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Synchronises on {@code claimLock} to ensure a consistent snapshot of rows matching the
     * {@code fireAt &lt;= now} predicate.
     */
    @Override
    public List<ScheduledCommandRecord> claimDue(int max, Instant now) {
        if (max <= 0)
            return List.of();
        Objects.requireNonNull(now, "now");
        synchronized (claimLock) {
            List<ScheduledCommandRecord> due = new ArrayList<>();
            for (ScheduledCommandRecord r : byId.values()) {
                if (r.status() == ScheduledCommandStatus.PENDING && !r.fireAt().isAfter(now)) {
                    due.add(r);
                }
            }
            due.sort(
                     Comparator.comparing(ScheduledCommandRecord::fireAt).thenComparing(r -> r.id().value()));
            if (due.size() > max) {
                due = new ArrayList<>(due.subList(0, max));
            }
            return List.copyOf(due);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@link ConcurrentHashMap#compute} to atomically update the row to DISPATCHED status.
     */
    @Override
    public void markDispatched(ScheduledCommandId id, Instant at) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
        byId.compute(
                     id,
                     (k, prior) -> {
                         if (prior == null)
                             return null;
                         return new ScheduledCommandRecord(
                                 prior.id(),
                                 prior.command(),
                                 prior.fireAt(),
                                 ScheduledCommandStatus.DISPATCHED,
                                 prior.attempt() + 1,
                                 prior.lastError(),
                                 prior.createdAt(),
                                 at);
                     });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@link ConcurrentHashMap#compute} to atomically update the row to reschedule it with
     * the new {@code fireAt}.
     */
    @Override
    public void rescheduleAfterFailure(ScheduledCommandId id, Instant nextFireAt, String error) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nextFireAt, "nextFireAt");
        Objects.requireNonNull(error, "error");
        byId.compute(
                     id,
                     (k, prior) -> {
                         if (prior == null)
                             return null;
                         // The interface does not pass a separate "now" for updatedAt,
                         // so we use nextFireAt as the best available approximation.
                         return new ScheduledCommandRecord(
                                 prior.id(),
                                 prior.command(),
                                 nextFireAt,
                                 ScheduledCommandStatus.PENDING,
                                 prior.attempt() + 1,
                                 error,
                                 prior.createdAt(),
                                 nextFireAt);
                     });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@link ConcurrentHashMap#compute} to atomically update the row to FAILED_TERMINAL
     * status.
     */
    @Override
    public void markFailedTerminal(ScheduledCommandId id, String error, Instant at) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(error, "error");
        byId.compute(
                     id,
                     (k, prior) -> {
                         if (prior == null)
                             return null;
                         return new ScheduledCommandRecord(
                                 prior.id(),
                                 prior.command(),
                                 prior.fireAt(),
                                 ScheduledCommandStatus.FAILED_TERMINAL,
                                 prior.attempt() + 1,
                                 error,
                                 prior.createdAt(),
                                 at);
                     });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Performs a thread-safe lookup using {@code ConcurrentHashMap.get()}.
     */
    @Override
    public Optional<ScheduledCommandRecord> find(ScheduledCommandId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Returns the total number of rows regardless of status. Intended for test assertions and
     * debugging.
     *
     * @return the number of rows stored
     */
    public int size() {
        return byId.size();
    }
}

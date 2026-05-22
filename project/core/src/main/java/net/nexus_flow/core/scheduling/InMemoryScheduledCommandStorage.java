package net.nexus_flow.core.scheduling;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

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

    private final ConcurrentHashMap<ScheduledCommandId, ScheduledCommandRecord> scheduledCommandIndex =
            new ConcurrentHashMap<>();
    /**
     * {@link java.util.concurrent.locks.ReentrantLock} (not intrinsic monitor) — same trade-
     * off as {@code InMemoryOutboxStorage.claimLock}: claim path contention under sustained
     * worker drain + scheduler push benefits from AQS-based parking.
     */
    private final java.util.concurrent.locks.ReentrantLock                      claimLock             =
            new java.util.concurrent.locks.ReentrantLock();

    /**
     * Lock-free index of every {@link ScheduledCommandStatus#PENDING} row ordered by
     * {@code (fireAt ASC, id ASC)}. Reduces {@link #claimDue} from O(N log N) (scan-then-sort
     * over every row) to O(K log N) (iterate the head of the skiplist until {@code fireAt} crosses
     * {@code now} or {@code max} rows are collected).
     */
    private static final Comparator<ScheduledCommandRecord> FIRE_AT_ORDER =
            Comparator.comparing(ScheduledCommandRecord::fireAt)
                    .thenComparing(r -> r.id().value());

    private final ConcurrentSkipListSet<ScheduledCommandRecord> pendingByFireAt =
            new ConcurrentSkipListSet<>(FIRE_AT_ORDER);

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
        final ScheduledCommandDuplicateException[] dup      = {null};
        final ScheduledCommandRecord[]             priorRef = {null};
        scheduledCommandIndex.compute(
                                      commandRecord.id(),
                                      (k, prior) -> {
                                          if (prior != null && prior.status() == ScheduledCommandStatus.PENDING) {
                                              dup[0] = new ScheduledCommandDuplicateException(commandRecord.id());
                                              return prior; // leave the existing row untouched
                                          }
                                          priorRef[0] = prior;
                                          return commandRecord; // insert or replace terminal row
                                      });
        if (dup[0] != null) {
            throw dup[0];
        }
        // The compute lambda replaced a terminal row: drop the stale index entry first.
        if (priorRef[0] != null) {
            pendingByFireAt.remove(priorRef[0]);
        }
        pendingByFireAt.add(commandRecord);
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
        claimLock.lock();
        try {
            List<ScheduledCommandRecord>     due  = new ArrayList<>(max < 16 ? max : 16);
            Iterator<ScheduledCommandRecord> iter = pendingByFireAt.iterator();
            while (iter.hasNext() && due.size() < max) {
                ScheduledCommandRecord candidate = iter.next();
                // The skiplist is ordered by fireAt; the first row whose fireAt is after `now`
                // terminates the head walk — every subsequent row would also fail the predicate.
                if (candidate.fireAt().isAfter(now)) {
                    break;
                }
                // Re-check status under the snapshot — concurrent markDispatched /
                // markFailedTerminal may have transitioned the row between the index read and
                // this point. Stale references are dropped from the index here so the next
                // claim cycle does not revisit them.
                ScheduledCommandRecord current = scheduledCommandIndex.get(candidate.id());
                if (current == null || current.status() != ScheduledCommandStatus.PENDING) {
                    pendingByFireAt.remove(candidate);
                    continue;
                }
                due.add(current);
            }
            return List.copyOf(due);
        } finally {
            claimLock.unlock();
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
        final ScheduledCommandRecord[] priorRef = {null};
        scheduledCommandIndex.compute(
                                      id,
                                      (k, prior) -> {
                                          if (prior == null)
                                              return null;
                                          priorRef[0] = prior;
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
        if (priorRef[0] != null && priorRef[0].status() == ScheduledCommandStatus.PENDING) {
            pendingByFireAt.remove(priorRef[0]);
        }
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
        final ScheduledCommandRecord[] priorRef = {null};
        final ScheduledCommandRecord[] nextRef  = {null};
        scheduledCommandIndex.compute(
                                      id,
                                      (k, prior) -> {
                                          if (prior == null)
                                              return null;
                                          priorRef[0] = prior;
                                          ScheduledCommandRecord next = new ScheduledCommandRecord(
                                                  prior.id(),
                                                  prior.command(),
                                                  nextFireAt,
                                                  ScheduledCommandStatus.PENDING,
                                                  prior.attempt() + 1,
                                                  error,
                                                  prior.createdAt(),
                                                  nextFireAt);
                                          nextRef[0] = next;
                                          return next;
                                      });
        if (priorRef[0] != null) {
            if (priorRef[0].status() == ScheduledCommandStatus.PENDING) {
                pendingByFireAt.remove(priorRef[0]);
            }
            if (nextRef[0] != null) {
                pendingByFireAt.add(nextRef[0]);
            }
        }
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
        final ScheduledCommandRecord[] priorRef = {null};
        scheduledCommandIndex.compute(
                                      id,
                                      (k, prior) -> {
                                          if (prior == null)
                                              return null;
                                          priorRef[0] = prior;
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
        if (priorRef[0] != null && priorRef[0].status() == ScheduledCommandStatus.PENDING) {
            pendingByFireAt.remove(priorRef[0]);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Performs a thread-safe lookup using {@code ConcurrentHashMap.get()}.
     */
    @Override
    public Optional<ScheduledCommandRecord> find(ScheduledCommandId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(scheduledCommandIndex.get(id));
    }

    /**
     * Returns the total number of rows regardless of status. Intended for test assertions and
     * debugging.
     *
     * @return the number of rows stored
     */
    public int size() {
        return scheduledCommandIndex.size();
    }
}

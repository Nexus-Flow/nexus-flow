package net.nexus_flow.core.outbox;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * in-memory implementation of {@link OutboxStorage} for tests and single-node demos.
 *
 * <p><strong>Not production grade.</strong> The backing {@link ConcurrentHashMap} is JVM-local;
 * rows do not survive a restart. Use a {@code nexus-flow-jdbc} backend for any deployment that
 * needs at-least-once delivery across crashes.
 *
 * <p>Concurrency model: append, status transitions and claim are all serialized through the per-key
 * atomic ops of {@code ConcurrentHashMap} ({@code compute}, {@code computeIfAbsent}) plus a single
 * coarse-grained lock for {@link #claimBatch(int, Instant)}. The lock is intentional: claimBatch
 * must observe a consistent snapshot of eligible rows and flip them all atomically.
 */
public final class InMemoryOutboxStorage implements OutboxStorage {

    private final ConcurrentHashMap<OutboxId, OutboxRecord>   byId             = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IdempotencyKey, OutboxId> byIdempotencyKey =
            new ConcurrentHashMap<>();
    /**
     * Per-row claim timestamp. Not part of {@link OutboxRecord} (which is frozen wire/persistence
     * shape) — this side map lets the visibility-timeout sweep identify rows whose claim is older
     * than the configured threshold without expanding the record's field surface.
     */
    private final ConcurrentHashMap<OutboxId, Instant>        claimedAt        = new ConcurrentHashMap<>();
    private final Object                                      claimLock        = new Object();
    private final Clock                                       clock;

    /** Constructs an instance backed by {@link Clock#systemUTC()}. */
    public InMemoryOutboxStorage() {
        this(Clock.systemUTC());
    }

    /**
     * Constructs an instance with a custom clock for deterministic tests.
     *
     * @param clock clock used to stamp {@code lastAttemptAt} on every status transition; must not be
     *              {@code null}
     */
    public InMemoryOutboxStorage(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void append(OutboxRecord outboxRecord) {
        Objects.requireNonNull(outboxRecord, "record");
        // FAILED_TERMINAL rows may be overwritten (manual replay path).
        // Any other status raises OutboxDuplicateKeyException.
        synchronized (claimLock) {
            OutboxId existingId = byIdempotencyKey.get(outboxRecord.idempotencyKey());
            if (existingId != null) {
                OutboxRecord existing = byId.get(existingId);
                if (existing != null && existing.status() != OutboxStatus.FAILED_TERMINAL) {
                    throw new OutboxDuplicateKeyException(outboxRecord.idempotencyKey());
                }
                if (existing != null) {
                    byId.remove(existingId);
                }
            }
            byId.put(outboxRecord.outboxId(), outboxRecord);
            byIdempotencyKey.put(outboxRecord.idempotencyKey(), outboxRecord.outboxId());
        }
    }

    @Override
    public List<OutboxRecord> claimBatch(int max, Instant now) {
        if (max <= 0) {
            return List.of();
        }
        Objects.requireNonNull(now, "now");
        synchronized (claimLock) {
            List<OutboxRecord> eligible =
                    byId.values().stream()
                            .filter(r -> r.status() == OutboxStatus.PENDING)
                            .filter(r -> r.nextRetryAt() == null || !r.nextRetryAt().isAfter(now))
                            .sorted(
                                    Comparator.comparing(OutboxRecord::recordedAt)
                                            .thenComparing(OutboxRecord::outboxId))
                            .limit(max)
                            .toList();
            List<OutboxRecord> claimed  = new ArrayList<>(eligible.size());
            for (OutboxRecord r : eligible) {
                // Re-check the row's status under per-key atomicity. The stream
                // snapshot above is weakly consistent: a concurrent markPublished /
                // markFailed / markFailedTerminal can transition the row between
                // the snapshot read and this point. An unconditional byId.put would
                // silently overwrite terminal states (PUBLISHED / FAILED_TERMINAL)
                // and markFailed-rescheduled PENDING rows back to IN_FLIGHT.
                OutboxRecord flipped =
                        byId.computeIfPresent(
                                              r.outboxId(),
                                              (k, current) -> {
                                                  if (current.status() != OutboxStatus.PENDING) {
                                                      return current;
                                                  }
                                                  if (current.nextRetryAt() != null && current.nextRetryAt().isAfter(now)) {
                                                      return current;
                                                  }
                                                  return current.withStatus(OutboxStatus.IN_FLIGHT);
                                              });
                if (flipped != null && flipped.status() == OutboxStatus.IN_FLIGHT) {
                    claimedAt.put(flipped.outboxId(), now);
                    claimed.add(flipped);
                }
            }
            return List.copyOf(claimed);
        }
    }

    @Override
    public void markPublished(OutboxId id) {
        Objects.requireNonNull(id, "id");
        Instant attemptAt = clock.instant();
        byId.compute(
                     id,
                     (k, current) -> {
                         requireExists(k, current);
                         requireResolvableFromInFlight(k, current);
                         return current.asPublished(attemptAt);
                     });
        claimedAt.remove(id);
    }

    @Override
    public void markFailed(OutboxId id, Throwable cause, Instant nextRetryAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(nextRetryAt, "nextRetryAt");
        String  flattened = flatten(cause);
        Instant attemptAt = clock.instant();
        byId.compute(
                     id,
                     (k, current) -> {
                         requireExists(k, current);
                         requireResolvableFromInFlight(k, current);
                         return current.asRetrying(flattened, attemptAt, nextRetryAt);
                     });
        claimedAt.remove(id);
    }

    @Override
    public void markFailedTerminal(OutboxId id, Throwable cause) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cause, "cause");
        String  flattened = flatten(cause);
        Instant attemptAt = clock.instant();
        byId.compute(
                     id,
                     (k, current) -> {
                         requireExists(k, current);
                         requireResolvableFromInFlight(k, current);
                         return current.asFailedTerminal(flattened, attemptAt);
                     });
        claimedAt.remove(id);
    }

    /**
     * Releases an {@link OutboxStatus#IN_FLIGHT} row back to {@link OutboxStatus#PENDING} without
     * incrementing the attempt counter. Rows already in a non-{@code IN_FLIGHT} state are left
     * unchanged.
     *
     * @param id surrogate key of the row to release; must not be {@code null}
     */
    @Override
    public void releaseToReady(OutboxId id) {
        Objects.requireNonNull(id, "id");
        byId.computeIfPresent(
                              id,
                              (k, current) -> {
                                  if (current.status() != OutboxStatus.IN_FLIGHT)
                                      return current;
                                  return current.asPending(null);
                              });
        claimedAt.remove(id);
    }

    /**
     * Visibility-timeout sweep — flips {@link OutboxStatus#IN_FLIGHT} rows whose claim is
     * older than {@code staleAfter} back to {@link OutboxStatus#PENDING} without
     * incrementing the attempt counter. Uses the per-row claim timestamp from the side map
     * to identify stale claims.
     */
    @Override
    public int sweepStaleClaims(java.time.Duration staleAfter, Instant now) {
        Objects.requireNonNull(staleAfter, "staleAfter");
        Objects.requireNonNull(now, "now");
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive: " + staleAfter);
        }
        Instant cutoff    = now.minus(staleAfter);
        int     recovered = 0;
        for (var entry : claimedAt.entrySet()) {
            OutboxId id           = entry.getKey();
            Instant  claimInstant = entry.getValue();
            if (claimInstant.isAfter(cutoff)) {
                continue;
            }
            OutboxRecord updated = byId.computeIfPresent(id, (k, current) -> {
                if (current.status() != OutboxStatus.IN_FLIGHT) {
                    return current;
                }
                return current.asPending(null);
            });
            if (updated != null && updated.status() == OutboxStatus.PENDING) {
                claimedAt.remove(id, claimInstant);
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * Atomically removes every {@link OutboxStatus#PUBLISHED} row whose {@code recordedAt} is
     * strictly less than {@code olderThan}. Returns the count.
     *
     * <p>Implementation walks the entry set, removes qualifying rows via {@code byId.remove} and also
     * removes the corresponding {@code byIdempotencyKey} mapping so a future re-append of the same
     * idempotency key starts cleanly. The walk runs under {@code claimLock} so it serialises against
     * {@code append} and {@code claimBatch} — concurrent appends of new rows during the sweep are
     * safe because their {@code recordedAt} is &gt;= the sweep's observation window.
     */
    @Override
    public int purgePublishedOlderThan(Instant olderThan) {
        Objects.requireNonNull(olderThan, "olderThan");
        int removed = 0;
        synchronized (claimLock) {
            var it = byId.entrySet().iterator();
            while (it.hasNext()) {
                var          e = it.next();
                OutboxRecord r = e.getValue();
                if (r.status() == OutboxStatus.PUBLISHED && r.recordedAt().isBefore(olderThan)) {
                    it.remove();
                    byIdempotencyKey.remove(r.idempotencyKey());
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Non-destructive replay scan. Returns every PENDING / IN_FLIGHT / PUBLISHED row whose
     * {@link OutboxRecord#sequenceNo()} is strictly greater than {@code sinceSequence}, in
     * ascending sequence order, capped at {@code max} rows. Terminal failures
     * ({@link OutboxStatus#FAILED_TERMINAL}) are excluded.
     */
    @Override
    public List<OutboxRecord> findSinceSequence(long sinceSequence, int max) {
        if (max <= 0) {
            return List.of();
        }
        return byId.values().stream()
                .filter(r -> r.sequenceNo() > sinceSequence)
                .filter(r -> r.status() != OutboxStatus.FAILED_TERMINAL)
                .sorted(Comparator.comparingLong(OutboxRecord::sequenceNo)
                        .thenComparing(OutboxRecord::outboxId))
                .limit(max)
                .toList();
    }

    /**
     * Returns the {@link OutboxRecord} for the given {@link OutboxId}, or {@code null} if absent.
     *
     * <p>Intended for tests and diagnostics only; not part of the {@link OutboxStorage} contract.
     *
     * @param id the surrogate key to look up
     * @return the record, or {@code null}
     */
    public OutboxRecord findById(OutboxId id) {
        return byId.get(id);
    }

    /**
     * Returns an unmodifiable snapshot of all rows currently held in memory.
     *
     * <p>Intended for tests and diagnostics only; not part of the {@link OutboxStorage} contract.
     *
     * @return snapshot of all rows in unspecified order
     */
    public List<OutboxRecord> snapshot() {
        return List.copyOf(byId.values());
    }

    /**
     * Returns the number of rows currently held in memory.
     *
     * <p>Intended for tests and diagnostics only.
     *
     * @return row count
     */
    public int size() {
        return byId.size();
    }

    // --- helpers ------------------------------------------------------

    private static void requireExists(OutboxId id, OutboxRecord current) {
        if (current == null) {
            throw new IllegalOutboxTransitionException("outboxId " + id + " is not present in storage");
        }
    }

    private static void requireResolvableFromInFlight(OutboxId id, OutboxRecord current) {
        // Allow IN_FLIGHT (the normal path) and PENDING (e.g. a worker
        // that did not go through claimBatch — this path should not happen, but we
        // keep the contract permissive). Forbid terminal states.
        OutboxStatus s = current.status();
        if (s == OutboxStatus.PUBLISHED || s == OutboxStatus.FAILED_TERMINAL) {
            throw new IllegalOutboxTransitionException(
                    "outboxId " + id + " is in terminal status " + s + "; further transitions are forbidden");
        }
    }

    private static String flatten(Throwable t) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        return sw.toString();
    }
}

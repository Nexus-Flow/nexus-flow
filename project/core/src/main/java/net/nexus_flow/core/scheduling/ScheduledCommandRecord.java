package net.nexus_flow.core.scheduling;

import java.time.Instant;
import java.util.Objects;
import net.nexus_flow.core.cqrs.command.Command;
import org.jspecify.annotations.Nullable;

/**
 * Durable row holding a command scheduled for future dispatch.
 *
 * <p>The record is the unit of storage AND the unit returned to the worker on {@link
 * ScheduledCommandStorage#claimDue(int, Instant)}. The in-memory backend holds the {@link Command}
 * reference directly; a future JDBC backend will serialize it through the same payload codec used
 * by the outbox.
 *
 * <p><strong>fire-at semantics:</strong> the worker MUST NOT dispatch the command before {@code
 * fireAt}. Slight positive drift (up to one {@link ScheduledCommandConfig#pollInterval()}) is
 * acceptable. The {@code clock} injected into {@link ScheduledCommandConfig} is used to obtain the
 * current instant for comparison; callers must ensure {@code clock} is pure (its {@code instant()}
 * method does not cache across ticks).
 *
 * <p><strong>concurrency:</strong> instances of this record are immutable and safe for concurrent
 * access from the worker thread and arbitrary caller threads. The worker polls storage and reads
 * rows on its own dedicated thread; multiple threads may call {@link
 * ScheduledCommandStorage#schedule(ScheduledCommandRecord)} concurrently.
 *
 * @param id        stable identity (primary key); must not be {@code null}
 * @param command   the command to dispatch when {@code fireAt} arrives; must not be {@code null}
 * @param fireAt    earliest dispatch instant; dispatch must not happen before this; must not be {@code
 *     null}
 * @param status    current lifecycle status; must not be {@code null}
 * @param attempt   number of dispatch attempts already performed (zero before first attempt); must be
 *                  {@code &gt;= 0}
 * @param lastError message of the last dispatch error, or {@code null} when none
 * @param createdAt wall-clock instant at row creation; must not be {@code null}
 * @param updatedAt wall-clock instant at the last status mutation; must not be {@code null}
 */
public record ScheduledCommandRecord(
                                     ScheduledCommandId id,
                                     Command<?> command,
                                     Instant fireAt,
                                     ScheduledCommandStatus status,
                                     int attempt,
                                     @Nullable String lastError,
                                     Instant createdAt,
                                     Instant updatedAt) {

    /**
     * Validates all fields.
     *
     * @throws NullPointerException     if any non-nullable field is {@code null}
     * @throws IllegalArgumentException if {@code attempt} is negative
     */
    public ScheduledCommandRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(fireAt, "fireAt");
        Objects.requireNonNull(status, "status");
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be >= 0: " + attempt);
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Factory for a fresh {@link ScheduledCommandStatus#PENDING} row with {@code attempt=0} and no
     * {@code lastError}.
     *
     * @param id      stable id for this scheduled command; must not be {@code null}
     * @param command the command to dispatch at {@code fireAt}; must not be {@code null}
     * @param fireAt  earliest dispatch instant; must not be {@code null}
     * @param now     current wall-clock instant (used for {@code createdAt} and {@code updatedAt}); must
     *                not be {@code null}
     * @return a new PENDING record
     */
    public static ScheduledCommandRecord create(
            ScheduledCommandId id, Command<?> command, Instant fireAt, Instant now) {
        return new ScheduledCommandRecord(
                id, command, fireAt, ScheduledCommandStatus.PENDING, 0, null, now, now);
    }
}

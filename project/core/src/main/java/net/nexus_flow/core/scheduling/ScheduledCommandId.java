package net.nexus_flow.core.scheduling;

import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.runtime.ids.FastUuid;

/**
 * Opaque, stable identity for a {@link ScheduledCommandRecord}.
 *
 * <p>The wrapped {@link UUID} is the primary key in both the in-memory backend and any future JDBC
 * backend ({@code id} column).
 *
 * <p><strong>Idempotent scheduling pattern:</strong> callers that derive the id deterministically
 * (e.g. from a domain event id) can safely attempt {@link
 * ScheduledCommandStorage#schedule(ScheduledCommandRecord)} multiple times — the storage layer
 * rejects the second call with {@link ScheduledCommandDuplicateException} if the first row is still
 * non-terminal. This provides exactly-once scheduling without a distributed lock.
 *
 * <p><strong>Concurrency:</strong> this record is immutable and safe for concurrent access from
 * multiple threads, including the worker thread and caller threads.
 *
 * @param value the underlying UUID; must not be {@code null}
 */
public record ScheduledCommandId(UUID value) {
    /** Validates that {@code value} is non-null. */
    public ScheduledCommandId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Generate a new random id.
     *
     * @return a fresh {@code ScheduledCommandId} backed by a random UUID
     */
    public static ScheduledCommandId random() {
        return new ScheduledCommandId(FastUuid.v4());
    }
}

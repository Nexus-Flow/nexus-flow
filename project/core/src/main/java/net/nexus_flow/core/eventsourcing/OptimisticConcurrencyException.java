package net.nexus_flow.core.eventsourcing;

import java.io.Serial;
import java.util.Objects;

/**
 * Thrown by {@link AggregateRepository#save} when the underlying {@link EventStore#append} returns
 * an {@link AppendResult.VersionConflict}.
 *
 * <p>Carries the {@code expected} and {@code actual} stream versions so callers (typically a
 * retry/rebase layer) can decide whether to re-load and rebase, or surface the failure to the user.
 *
 * <p>Recovery pattern: catch this exception, call {@link AggregateRepository#load(java.util.UUID)}
 * to get a fresh aggregate, re-apply the business command, then call {@link
 * AggregateRepository#save} again.
 */
public final class OptimisticConcurrencyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long expectedVersion;
    private final long actualVersion;

    /**
     * {@code StreamId} is a record of (String, UUID); marked {@code transient} because exceptions are
     * instantiated in-process and never marshaled.
     */
    private final transient StreamId stream;

    /**
     * @param stream          the stream on which the conflict occurred
     * @param expectedVersion the version the caller assumed
     * @param actualVersion   the version actually found in the store
     */
    public OptimisticConcurrencyException(StreamId stream, long expectedVersion, long actualVersion) {
        super(
              "optimistic concurrency conflict on "
                      + Objects.requireNonNull(stream, "stream")
                      + ": expected version="
                      + expectedVersion
                      + ", actual="
                      + actualVersion);
        this.stream          = stream;
        this.expectedVersion = expectedVersion;
        this.actualVersion   = actualVersion;
    }

    /** The version the caller assumed when the conflict was detected. */
    public long expectedVersion() {
        return expectedVersion;
    }

    /** The actual version found in the store at conflict time. */
    public long actualVersion() {
        return actualVersion;
    }

    /** The stream on which the conflict occurred. */
    public StreamId stream() {
        return stream;
    }
}

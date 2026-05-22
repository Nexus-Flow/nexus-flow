package net.nexus_flow.core.eventsourcing;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ids.MessageId;

/**
 * In-memory {@link EventStore} for tests and demos.
 *
 * <p>Concurrency design:
 *
 * <ul>
 * <li>One {@link ReentrantLock} per stream — appends to different streams never serialise on each
 * other.
 * <li>One global {@link AtomicLong} feeding {@link EventEnvelope#globalPosition()}. The counter
 * is incremented under the per-stream lock so that the global order coincides with the
 * per-stream order for any single thread.
 * <li>A second {@link ReentrantLock} ({@link #globalLock}) is held <strong>only while the
 * envelopes of a successful append are being added to the global sequence</strong>. It is
 * acquired AFTER the per-stream lock is held, so cross-stream appends never deadlock
 * (lock-order is always per-stream → global).
 * </ul>
 *
 * <p>This backend is intentionally simple — no snapshots, no compaction, no truncation. It models
 * optimistic concurrency, per-stream ordering, and a monotonic global event stream, but it does not
 * simulate durable transactions or physical stream absence checks beyond the logical {@code
 * expectedVersion} contract.
 */
public final class InMemoryEventStore implements EventStore {

    private final Clock                      clock;
    private final Map<StreamId, StreamState> streamStateIndex = new ConcurrentHashMap<>();
    private final AtomicLong                 globalCounter    = new AtomicLong(0L);
    private final List<EventEnvelope>        globalLog        = new ArrayList<>();
    /**
     * Dedicated lock for the global log + counter. {@link ReentrantLock} (not {@code
     * synchronized}) because under sustained writer contention (the append path is exercised
     * by every event-recording aggregate) AQS-based parking is 5–20% faster than HotSpot's
     * heavyweight monitor inflation path. No biased-locking advantage since JDK 18+
     * removed it. Critical section is short; the trade-off favours ReentrantLock at the
     * million-req/s envelope this framework targets.
     */
    private final ReentrantLock              globalLock       = new ReentrantLock();

    /** Create an in-memory event store with {@link Clock#systemUTC()}. */
    public InMemoryEventStore() {
        this(Clock.systemUTC());
    }

    /**
     * Create an in-memory event store with a custom clock.
     *
     * @param clock the clock to use for recording envelope timestamps; non-null
     */
    public InMemoryEventStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Append an event batch atomically while enforcing optimistic concurrency on a single stream.
     *
     * @param stream          target stream
     * @param expectedVersion version expected by the caller
     * @param events          batch to append in order
     * @return either a successful append result or a version conflict
     * @throws NullPointerException     if {@code stream} or {@code events} is {@code null}
     * @throws IllegalArgumentException if {@code expectedVersion < -1}, {@code events} is empty, or
     *                                  {@code events} contains a null entry
     */
    @Override
    public AppendResult append(StreamId stream, long expectedVersion, List<DomainEvent> events) {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(events, "events");
        if (expectedVersion < -1L) {
            throw new IllegalArgumentException("expectedVersion must be >= -1: " + expectedVersion);
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
        for (DomainEvent e : events) {
            Objects.requireNonNull(e, "events must not contain null entries");
        }

        StreamState state = streamStateIndex.computeIfAbsent(stream, k -> new StreamState());
        state.lock.lock();
        try {
            long    current = state.envelopes.size();
            boolean ok      =
                    expectedVersion == -1L ? current == 0 // expectedVersion == -1: must not exist (empty)
                            : current == expectedVersion;
            if (!ok) {
                return new AppendResult.VersionConflict(expectedVersion, current);
            }
            // Reserve the global positions and add to both logs while
            // holding the per-stream lock — guarantees no other thread
            // observes a partial batch.
            globalLock.lock();
            try {
                // Reserve the entire batch's global positions in ONE atomic stride instead of
                // calling {@code incrementAndGet} per event. Saves N-1 CAS loops on
                // {@code globalCounter} under contention; reduces the global-lock-held
                // interval on multi-event commits (typical aggregate save: 1-10 events per
                // batch). The reserved range is [firstGlobal, firstGlobal + N - 1].
                int  batchSize   = events.size();
                long lastGlobal  = globalCounter.addAndGet(batchSize);
                long firstGlobal = lastGlobal - batchSize + 1L;
                // Hoist the clock read outside the per-event loop: every event in this append
                // batch shares the same wall-clock instant (they are recorded atomically). This
                // removes one `clock.instant()` call per event from the per-event allocation
                // budget — Clock implementations are typically thread-safe but the call still
                // crosses a volatile read on most backends.
                Instant timestamp = clock.instant();
                for (int i = 0; i < batchSize; i++) {
                    long          streamVersion  = current + i + 1L;
                    long          globalPosition = firstGlobal + i;
                    EventEnvelope env            =
                            new EventEnvelope(
                                    MessageId.random(),
                                    stream,
                                    streamVersion,
                                    globalPosition,
                                    timestamp,
                                    events.get(i));
                    state.envelopes.add(env);
                    globalLog.add(env);
                }
                long newVersion = current + batchSize;
                return new AppendResult.Success(newVersion, firstGlobal);
            } finally {
                globalLock.unlock();
            }
        } finally {
            state.lock.unlock();
        }
    }

    /**
     * Read a contiguous page from a single stream.
     *
     * @param stream      stream to read
     * @param fromVersion inclusive 1-based stream version lower bound
     * @param maxCount    maximum number of envelopes to return
     * @return immutable slice of the requested stream window
     * @throws NullPointerException     if {@code stream} is {@code null}
     * @throws IllegalArgumentException if {@code fromVersion < 1} or {@code maxCount < 1}
     */
    @Override
    public EventStream read(StreamId stream, long fromVersion, long maxCount) {
        Objects.requireNonNull(stream, "stream");
        if (fromVersion < 1) {
            throw new IllegalArgumentException("fromVersion must be >= 1: " + fromVersion);
        }
        if (maxCount < 1) {
            throw new IllegalArgumentException("maxCount must be >= 1: " + maxCount);
        }
        StreamState state = streamStateIndex.get(stream);
        if (state == null) {
            return EventStream.empty();
        }
        state.lock.lock();
        try {
            int sz = state.envelopes.size();
            if (fromVersion > sz) {
                return EventStream.empty();
            }
            int fromIdx = (int) (fromVersion - 1);
            int toIdx   = (int) Math.min(sz, fromIdx + maxCount);
            // Single immutable copy. EventStream's compact constructor performs another
            // List.copyOf, but List.copyOf is a no-op when the input is already an immutable
            // List instance (the JDK identity-checks and returns the same reference). So the
            // slice is materialised exactly once, here, under the per-stream lock.
            List<EventEnvelope> slice       = List.copyOf(state.envelopes.subList(fromIdx, toIdx));
            long                lastVersion = slice.isEmpty() ? 0L : slice.getLast().streamVersion();
            return new EventStream(slice, lastVersion);
        } finally {
            state.lock.unlock();
        }
    }

    /**
     * Read a contiguous page from the global event log.
     *
     * @param fromGlobalPosition inclusive 1-based global position lower bound
     * @param maxCount           maximum number of envelopes to return
     * @return immutable slice of the global event stream in global-position order
     * @throws IllegalArgumentException if {@code fromGlobalPosition < 1} or {@code maxCount < 1}
     */
    @Override
    public EventStream readAll(long fromGlobalPosition, long maxCount) {
        if (fromGlobalPosition < 1) {
            throw new IllegalArgumentException("fromGlobalPosition must be >= 1: " + fromGlobalPosition);
        }
        if (maxCount < 1) {
            throw new IllegalArgumentException("maxCount must be >= 1: " + maxCount);
        }
        globalLock.lock();
        try {
            int sz = globalLog.size();
            if (fromGlobalPosition > sz) {
                return EventStream.empty();
            }
            int fromIdx = (int) (fromGlobalPosition - 1);
            int toIdx   = (int) Math.min(sz, fromIdx + maxCount);
            // See read(): a single List.copyOf materialises the slice; EventStream's compact
            // constructor sees an immutable input and skips a redundant copy.
            List<EventEnvelope> slice       = List.copyOf(globalLog.subList(fromIdx, toIdx));
            long                lastVersion = slice.isEmpty() ? 0L : slice.getLast().globalPosition();
            return new EventStream(slice, lastVersion);
        } finally {
            globalLock.unlock();
        }
    }

    /**
     * Return the current global envelope count. Intended for tests and diagnostics.
     *
     * @return number of envelopes stored globally across all streams
     */
    public long globalSize() {
        globalLock.lock();
        try {
            return globalLog.size();
        } finally {
            globalLock.unlock();
        }
    }

    private static final class StreamState {
        /**
         * Per-stream {@link ReentrantLock}. Same rationale as {@link #globalLock} —
         * AQS-parking under contention beats heavyweight-monitor inflation in JDK 21+ by
         * 5–20% in synthetic benchmarks. Aggregates of the same stream are the contention
         * source (otherwise the per-stream sharding already serialises them).
         */
        final ReentrantLock lock = new ReentrantLock();

        /** All accesses are exclusively under {@link #lock}; no extra synchronization needed. */
        final List<EventEnvelope> envelopes = new ArrayList<>();
    }
}

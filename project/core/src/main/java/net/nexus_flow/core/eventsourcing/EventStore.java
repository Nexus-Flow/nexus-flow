package net.nexus_flow.core.eventsourcing;

import java.util.List;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * Append-only log of {@link EventEnvelope} grouped by {@link StreamId}. The store is the SPI used
 * by the current in-memory implementation and by future JDBC, JPA, CDC, and Kafka-backed adapters,
 * so implementations may add stronger guarantees but must preserve this contract.
 *
 * <p>The store is consumed by:
 *
 * <ul>
 * <li>{@code AggregateRepository} — hydrates an {@link net.nexus_flow.core.ddd.Aggregate} by
 * replaying the envelopes of a single stream.
 * <li>{@code ProjectionRunner} — folds the global envelope sequence into read models.
 * <li>{@code SagaRunner} — observes envelopes to advance long-running process managers.
 * </ul>
 *
 * <p>Concurrency contract:
 *
 * <ul>
 * <li>Single-stream appends are serialized: two concurrent {@link #append(StreamId, long, List)}
 * calls on the same stream with the same {@code expectedVersion} yield exactly one {@link
 * AppendResult.Success} and exactly one {@link AppendResult.VersionConflict}.
 * <li>Cross-stream appends are independent: a slow append to stream A does NOT block appends to
 * stream B beyond whatever minimal coordination is required to allocate the global sequence.
 * <li>{@link #readAll(long, long)} returns envelopes in monotonic {@link
 * EventEnvelope#globalPosition()} order — the source of truth for read-model {@code
 *       Projection}s and saga catch-up.
 * </ul>
 *
 * <h2>Optimistic concurrency</h2>
 *
 * The {@code expectedVersion} parameter pins the optimistic-concurrency check:
 *
 * <ul>
 * <li>{@code expectedVersion == 0} — append succeeds only when the stream is empty (first event
 * on a fresh stream).
 * <li>{@code expectedVersion == -1} — append succeeds only when the stream does NOT exist at all.
 * Identical effect to {@code expectedVersion == 0} for in-memory backends; JDBC backends may
 * use this to assert physical absence (no row at all) rather than logical emptiness.
 * <li>{@code expectedVersion == N} (N&gt;=1) — append succeeds only when the current stream
 * version is exactly {@code N}.
 * </ul>
 */
public interface EventStore {

    /**
     * Genesis global position. The first envelope ever appended to a store gets {@link
     * EventEnvelope#globalPosition()} equal to this value. Used by {@code SagaRunner}, {@code
     * ProjectionRunner}, and any catch-up consumer that needs a cold-start cursor.
     *
     * <p>The constant is {@code 1L} (NOT zero) so a {@code 0} return from a checkpoint store
     * unambiguously means "never processed" rather than "processed the first event".
     */
    long FIRST_GLOBAL_POSITION = 1L;

    /**
     * Append a batch of events to {@code stream}, asserting the optimistic-concurrency check encoded
     * by {@code expectedVersion}.
     *
     * <p>The batch append is atomic: either every event is recorded with consecutive stream versions
     * and consecutive global positions, or no event is recorded at all. Implementations must never
     * expose a partially committed batch to {@link #read(StreamId, long, long)} or {@link
     * #readAll(long, long)}.
     *
     * <p>Idempotency is defined in terms of optimistic concurrency, not payload de-duplication. If a
     * caller retries the same logical command after a successful append, the retry must observe
     * either the original {@link AppendResult.Success} or a {@link AppendResult.VersionConflict}; it
     * must not create a duplicate second batch at the same expected version.
     *
     * @param stream          target stream id; in aggregate-backed usage the {@link StreamId} embeds the
     *                        aggregate type and aggregate id being appended to
     * @param expectedVersion {@code -1} (must not exist) | {@code 0} (must be empty) | {@code N>=1}
     *                        (must be at version N)
     * @param events          events in recording order; the batch is appended atomically — either every event
     *                        lands or none
     * @return {@link AppendResult.Success} on success, otherwise a {@link
     *         AppendResult.VersionConflict}
     * @throws NullPointerException     if {@code stream} or {@code events} is {@code null}
     * @throws IllegalArgumentException if {@code expectedVersion < -1}, {@code events} is empty, or
     *                                  {@code events} contains a null entry
     */
    AppendResult append(StreamId stream, long expectedVersion, List<DomainEvent> events);

    /**
     * Read a page of envelopes from a single stream.
     *
     * <p>This is the single-aggregate load-events operation used by {@link AggregateRepository}. The
     * {@link StreamId} already identifies the aggregate type and aggregate id, and {@code
     * fromVersion} applies an inclusive lower bound on the stream version so repositories can resume
     * replay from the version immediately after a snapshot.
     *
     * @param stream      stream to read; in aggregate-backed usage this filters by aggregate type and
     *                    aggregate id
     * @param fromVersion 1-based inclusive starting version
     * @param maxCount    maximum number of envelopes to return; must be &gt; 0
     * @return slice; empty if the stream does not exist or no envelope satisfies the requested
     *         version window
     * @throws NullPointerException     if {@code stream} is {@code null}
     * @throws IllegalArgumentException if {@code fromVersion < 1} or {@code maxCount < 1}
     */
    EventStream read(StreamId stream, long fromVersion, long maxCount);

    /**
     * Read a page of the global envelope sequence.
     *
     * <p>The returned slice is ordered by increasing {@link EventEnvelope#globalPosition()} and forms
     * the package's global event stream for projections and sagas that need to catch up across many
     * aggregates.
     *
     * @param fromGlobalPosition 1-based inclusive starting position
     * @param maxCount           maximum number of envelopes to return; must be &gt; 0
     * @return slice in {@link EventEnvelope#globalPosition()} order
     * @throws IllegalArgumentException if {@code fromGlobalPosition < 1} or {@code maxCount < 1}
     */
    EventStream readAll(long fromGlobalPosition, long maxCount);
}

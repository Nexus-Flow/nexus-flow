package net.nexus_flow.core.cqrs.command;

import java.time.Duration;

/**
 * Mutable back-off policy shared by an executor's queue-full retry paths.
 *
 * <p>Implementations carry mutable internal state (exponential depth, in-backoff flag) and MUST be
 * thread-safe — concurrent enqueuers fighting a full queue all read+advance the same instance. The
 * canonical implementation ({@link ExponentialBackoffStrategy}) uses {@code synchronized} methods
 * because the state is just two correlated primitives and a synchronized block gives the cleanest
 * atomic-group semantics. Callers do not need to wrap the strategy in any holder; the strategy IS
 * the holder.
 *
 * <p>Why mutable + {@code synchronized} rather than immutable + {@code AtomicReference} + CAS:
 *
 * <ul>
 * <li>The state is two primitives ({@code long} + {@code boolean}). Snapshot semantics are not
 * valuable; the natural API is "give me the next wait and advance" as a single atomic action.
 * <li>No allocation per advance.
 * <li>{@code reset()} atomically transitions BOTH fields back to base, which is impossible to
 * guarantee with separate atomic fields without an external lock.
 * <li>The path is cold (queue-full retry) so lock contention is irrelevant — every caller is
 * about to {@link Thread#sleep} anyway.
 * </ul>
 *
 * <p>The immutable + AtomicReference + CAS pattern is the canonical recipe for COMPLEX shared state
 * — multiple correlated fields, snapshot semantics, lock-free composition. It is overkill for two
 * primitives.
 *
 * <p>Configuration (base wait, max wait, cancellation poll) is supplied by {@link BackoffSettings}
 * at construction time and is immutable for the strategy's lifetime.
 */
interface BackoffStrategy {

    /**
     * Atomically read the current wait duration AND advance the internal state one exponential step.
     *
     * <p>Pure of side effects on the caller's thread — does NOT {@link Thread#sleep}. The caller
     * receives the wait duration and is responsible for the actual sleep so it owns interruption
     * semantics:
     *
     * <pre>{@code
     * Duration wait = strategy.nextWaitAndAdvance();
     * try {
     *   Thread.sleep(wait.toMillis());
     * } catch (InterruptedException ie) {
     *   Thread.currentThread().interrupt();
     *   ...
     * }
     * }</pre>
     *
     * @return the wait duration that was authorized BEFORE the internal state advanced; never {@code
     *     null}
     */
    Duration nextWaitAndAdvance();

    /**
     * @return {@code true} iff this strategy has advanced past its initial step at least once; {@code
     *     false} when the strategy is in (or has been reset to) its base state.
     */
    boolean isInBackoffState();

    /**
     * Atomically reset the strategy to its base state. Both internal fields ({@code waitMillis} and
     * the in-backoff flag) transition together; readers observe either the pre-reset or post-reset
     * snapshot, never a mixed one.
     */
    void reset();

    /**
     * Cooperative cancellation poll interval used by {@code *HandlerExecutor} drain loops to bound
     * the maximum wait between two cancellation/deadline checks on the head of the priority queue.
     *
     * <p>This value is configuration, not state — it does NOT change across {@link
     * #nextWaitAndAdvance()} / {@link #reset()} transitions in canonical implementations. The default
     * of 25 ms is an order of magnitude below typical handler latencies, yet large enough to avoid
     * busy-spinning under steady state.
     *
     * @return the poll interval; never {@code null}, always strictly positive
     */
    Duration cancellationPoll();
}

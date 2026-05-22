package net.nexus_flow.core.runtime.result;

import java.util.List;
import java.util.Objects;
import net.nexus_flow.core.runtime.ExecutionMode;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.ids.MessageId;

/**
 * Sealed return type of any dispatch through the {@link FlowRuntime}.
 *
 * <p>The three variants encode the contract from the dispatch contract:
 *
 * <ul>
 * <li>{@link Success} — the dispatch (and every nested fork it owned) completed normally,
 * producing {@code value}.
 * <li>{@link Failure} — the dispatch terminated with a single {@code cause}; under {@code
 *       FailFast} this is the only failure observable to the caller, with siblings canceled.
 * <li>{@link PartialFailure} — under {@code CollectFailures} or {@code IsolatePerBoundary}: the
 * dispatch produced a (possibly partial) {@code value} together with the list of failures
 * that were tolerated by the active policy.
 * </ul>
 *
 * <p>This interface defines the type and its constructors; runtime-side production of {@link
 * PartialFailure} is delegated to the {@code ErrorPolicy} implementation.
 *
 * @param <T> the success payload type
 */
public sealed interface DispatchResult<T>
        permits DispatchResult.Success,
        DispatchResult.Failure,
        DispatchResult.PartialFailure,
        DispatchResult.Accepted {

    /** {@code true} when this is a {@link Success}. */
    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    /** {@code true} when this is a {@link Failure}. */
    default boolean isFailure() {
        return this instanceof Failure<T>;
    }

    /** {@code true} when this is a {@link PartialFailure}. */
    default boolean isPartialFailure() {
        return this instanceof PartialFailure<T>;
    }

    /**
     * {@code true} when this is an {@link Accepted} (durable async hand-off — the actual delivery
     * happens later through the outbox worker, not on the caller thread).
     */
    default boolean isAccepted() {
        return this instanceof Accepted<T>;
    }

    /**
     * Cached {@link Success}{@code <Void>} carrying {@code null}. Event-dispatch hot paths
     * return this billions of times per second on a saturated event bus; reusing a single
     * instance saves one record allocation per dispatch.
     */
    Success<Void> SUCCESS_VOID = new Success<>(null);

    /** Build a {@link Success}. */
    @SuppressWarnings("unchecked")
    static <T> DispatchResult<T> success(T value) {
        // Fast path: every Success<Void> on the event-dispatch hot path resolves to the
        // pre-built {@link #SUCCESS_VOID} singleton. The cast is safe — Success<Void>'s
        // erasure is Success<Object>, and consumers downcast based on the parametric T at
        // their own call site (the success(null) factory is only invoked by callers that
        // can prove their T is Void).
        if (value == null) {
            return (DispatchResult<T>) SUCCESS_VOID;
        }
        return new Success<>(value);
    }

    /** Build a {@link Failure}. */
    static <T> DispatchResult<T> failure(Throwable cause) {
        return new Failure<>(cause);
    }

    /** Build a {@link PartialFailure}. */
    static <T> DispatchResult<T> partial(T value, List<Throwable> failures) {
        return new PartialFailure<>(value, failures);
    }

    /** build an {@link Accepted} carrying the {@link MessageId} of the durably-queued message. */
    static <T> DispatchResult<T> accepted(MessageId messageId) {
        return new Accepted<>(messageId);
    }

    /** Successful dispatch. */
    record Success<T>(T value) implements DispatchResult<T> {
    }

    /**
     * Failed dispatch with a single root cause.
     *
     * <p>Concurrent failures collapsed under {@code FailFast} attach the losing causes through {@link
     * Throwable#addSuppressed(Throwable)}.
     */
    record Failure<T>(Throwable cause) implements DispatchResult<T> {
        public Failure {
            Objects.requireNonNull(cause, "cause");
        }
    }

    /**
     * Mixed outcome: a (possibly partial) value plus the list of failures tolerated by the active
     * {@code ErrorPolicy}.
     *
     * <p>Under {@code CollectFailures} {@code value} may be a default / empty / aggregated value;
     * under {@code IsolatePerBoundary} it is the value of the surrounding boundary while {@code
     * failures} holds the contained failures.
     */
    record PartialFailure<T>(T value, List<Throwable> failures) implements DispatchResult<T> {
        public PartialFailure {
            Objects.requireNonNull(failures, "failures");
            if (failures.isEmpty()) {
                throw new IllegalArgumentException(
                        "PartialFailure requires at least one failure; use Success otherwise");
            }
            // Defensive immutable copy.
            failures = List.copyOf(failures);
        }
    }

    /**
     * durable async hand-off receipt. Returned by the {@link ExecutionMode#asynchronousDurable()
     * AsynchronousDurable} dispatch path: the message has been persisted to the durable outbox and
     * the {@link MessageId} is the only handle the caller gets. The actual handler invocation happens
     * asynchronously when the outbox worker drains the row.
     *
     * <p>Carries no value type instance: the success / failure of the eventual delivery is observed
     * through the outbox row status and the inbox dedupe markers, not through this synchronous
     * result.
     */
    record Accepted<T>(MessageId messageId) implements DispatchResult<T> {
        public Accepted {
            Objects.requireNonNull(messageId, "messageId");
        }
    }
}

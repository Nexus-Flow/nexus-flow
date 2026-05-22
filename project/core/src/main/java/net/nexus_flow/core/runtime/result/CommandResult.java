package net.nexus_flow.core.runtime.result;

import java.util.List;
import java.util.Objects;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * Outcome of a single command handler invocation.
 *
 * <p>{@code CommandResult} is the value-typed return of the handler API. It is intentionally
 * narrower than {@link DispatchResult}: a handler reports either a domain success or a domain
 * failure, while the runtime is the one that aggregates per-policy semantics and turns results into
 * a {@link DispatchResult} for the caller.
 *
 * <p><b>Option A event recording.</b> {@link Success} carries the list of {@link DomainEvent}s
 * recorded by the aggregate during the handler run, drained explicitly through {@code
 * Aggregate#drainEvents()}. Returning events on the result object lets the runtime publish them
 * deterministically <em>after</em> the handler completes, avoiding the cross-flow leakage caused by
 * the JVM-wide {@link net.nexus_flow.core.cqrs.event.DomainEventContext} sink. Handlers that have
 * not migrated yet are free to return {@link #success(Object)} with no events; the JVM-wide sink
 * remains the fallback drain path for backward compatibility.
 *
 * @param <R> the domain value produced on success
 */
public sealed interface CommandResult<R> permits CommandResult.Success, CommandResult.Failure {

    /** {@code true} when this is a {@link Success}. */
    boolean isSuccess();

    /** {@code true} when this is a {@link Failure}. */
    default boolean isFailure() {
        return !isSuccess();
    }

    /** Wrap a domain value into a {@link Success} with no recorded events. */
    static <R> CommandResult<R> success(R value) {
        return new Success<>(value, List.of());
    }

    /** Wrap a domain value plus its recorded events into a {@link Success}. */
    static <R> CommandResult<R> success(R value, List<DomainEvent> events) {
        return new Success<>(value, events);
    }

    /** Wrap a {@link Throwable} into a {@link Failure}. */
    static <R> CommandResult<R> failure(Throwable cause) {
        return new Failure<>(cause);
    }

    /**
     * A successful command outcome carrying the produced value and the list of events recorded by the
     * aggregate during the handler run.
     *
     * <p>The events list is defensively copied and immutable; callers may safely retain it across
     * thread boundaries.
     */
    record Success<R>(R value, List<DomainEvent> events) implements CommandResult<R> {
        public Success {
            Objects.requireNonNull(events, "events");
            events = List.copyOf(events);
        }

        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    /**
     * A failed command outcome.
     *
     * <p>The cause is preserved verbatim and does <em>not</em> wrap domain errors.
     * Technical-vs-domain classification is exposed through {@link FlowError} when the runtime turns
     * this result into a {@code DispatchResult.Failure}.
     */
    record Failure<R>(Throwable cause) implements CommandResult<R> {
        public Failure {
            Objects.requireNonNull(cause, "cause");
        }

        @Override
        public boolean isSuccess() {
            return false;
        }
    }
}

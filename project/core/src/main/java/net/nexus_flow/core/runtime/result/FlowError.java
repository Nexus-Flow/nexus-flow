package net.nexus_flow.core.runtime.result;

import java.io.Serial;
import java.util.List;
import java.util.Objects;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;

/**
 * Classification of a failure observed by the runtime.
 *
 * <p>A sealed marker interface whose variants are themselves throwables, so a {@code
 * DispatchResult.Failure(FlowError.Technical(...))} fits the {@code
 * DispatchResult.Failure(Throwable)} shape directly.
 *
 * <p>Variants:
 *
 * <ul>
 * <li>{@link Domain} — <em>marker</em> interface (non-sealed) for expected, modeled business
 * outcomes. User exceptions opt in by declaring {@code implements FlowError.Domain}; the
 * runtime sees them as domain errors and propagates them <strong>without wrapping</strong>.
 * <li>{@link Technical} — unexpected infrastructure or programming failure. {@code
 *       RuntimeException} subclass that always carries both the original cause and the {@link
 * ExecutionContext} active at the failure site.
 * <li>{@link Aggregated} — concurrent failures collected under {@link
 * ErrorPolicy.CollectFailures}, or losing causes attached as suppressed under {@link
 * ErrorPolicy.FailFast}. Preserves every cause through {@link
 * Throwable#addSuppressed(Throwable)}.
 * </ul>
 */
public sealed interface FlowError
        permits FlowError.Domain, FlowError.Technical, FlowError.Aggregated {

    /**
     * Expected, modeled domain failure. <strong>Marker interface</strong> — concrete domain
     * exceptions implement it to opt into the "no-wrapping" path.
     */
    non-sealed interface Domain extends FlowError {
        /**
         * Convenience accessor: domain errors are throwables.
         *
         * @return {@code this} cast to {@link Throwable}; implementations are required to extend a
         *         {@link Throwable} subtype.
         */
        default Throwable asThrowable() {
            return (Throwable) this;
        }
    }

    /**
     * Unexpected technical failure. Carries the original cause and the {@link ExecutionContext}
     * active at the failure site.
     *
     * <p>— declared {@code non-sealed} (was {@code final}) so back-pressure exceptions such as {@link
     * net.nexus_flow.core.cqrs.command.SaturationRejectedException} can extend it while still being
     * classified as {@code FlowError.Technical} by pattern-matching consumers.
     */
    non-sealed class Technical extends RuntimeException implements FlowError {
        @Serial
        private static final long serialVersionUID = 1L;

        private final transient ExecutionContext executionContext;

        /**
         * Creates a technical failure with an explicit message.
         *
         * @param message          human-readable description of the failure; typically names the handler class
         *                         or interceptor and the operation that failed
         * @param cause            the underlying exception; never {@code null}
         * @param executionContext the {@link ExecutionContext} active at the moment of failure; never
         *                         {@code null}
         * @throws NullPointerException if {@code cause} or {@code executionContext} is {@code null}
         */
        public Technical(String message, Throwable cause, ExecutionContext executionContext) {
            // Stack-traceless wrapper: the cause already carries the real stack trace; this
            // wrapper is just the classification + ExecutionContext carrier and its own
            // trace would point to {@code classify()} at the dispatcher boundary, never to
            // the actual failure site. Suppression chain stays active so {@code
            // ThrowableUtils.withSuppressed} can attach losing causes. JMH validates the
            // classify(plainRuntime) hot path drops from ~505 ns to ~50 ns with this flag.
            super(
                  message,
                  Objects.requireNonNull(cause, "cause"),
                  /* enableSuppression= */ true,
                  /* writableStackTrace= */ false);
            this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
        }

        /**
         * Creates a technical failure using the cause's own message.
         *
         * @param cause            the underlying exception; its {@link Throwable#getMessage()} becomes this
         *                         exception's message; never {@code null}
         * @param executionContext the {@link ExecutionContext} active at the moment of failure; never
         *                         {@code null}
         * @throws NullPointerException if {@code cause} or {@code executionContext} is {@code null}
         */
        public Technical(Throwable cause, ExecutionContext executionContext) {
            this(cause.getMessage(), cause, executionContext);
        }

        /** Execution context active at the moment of failure. Never {@code null}. */
        public ExecutionContext executionContext() {
            return executionContext;
        }
    }

    /**
     * Multiple failures collapsed into a single throwable.
     *
     * <p>Semantics:
     *
     * <ul>
     * <li>The list is defensively copied and exposed through {@link #failures()}.
     * <li>Every failure beyond the first is attached as a suppressed exception so {@link
     * Throwable#getSuppressed()} works for tooling that does not know about {@code FlowError}.
     * <li>The first failure is set as the {@link Throwable#getCause()} to keep stack-trace printing
     * readable.
     * </ul>
     */
    final class Aggregated extends RuntimeException implements FlowError {
        @Serial
        private static final long serialVersionUID = 1L;

        // List<Throwable> contains user-supplied causes that may not be Serializable,
        // so failures are intentionally excluded from Java serialization.
        private final transient List<Throwable> failures;

        /**
         * Creates an aggregated failure from an ordered list of causes.
         *
         * <p>The first failure becomes the {@link Throwable#getCause()} for readable stack traces.
         * Every subsequent failure is attached as a suppressed exception so tooling that does not know
         * about {@code FlowError.Aggregated} can still enumerate all causes.
         *
         * @param failures ordered list of failures; must contain at least one element; never {@code
         *     null}
         * @throws IllegalArgumentException if {@code failures} is empty
         * @throws NullPointerException     if {@code failures} is {@code null}
         */
        public Aggregated(List<Throwable> failures) {
            super(
                  "Aggregated " + Objects.requireNonNull(failures, "failures").size() + " failures",
                  failures.isEmpty() ? null : failures.getFirst());
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Aggregated requires at least one failure");
            }
            this.failures = List.copyOf(failures);
            for (int i = 1; i < this.failures.size(); i++) {
                addSuppressed(this.failures.get(i));
            }
        }

        /** Immutable snapshot of every failure observed by the policy. */
        public List<Throwable> failures() {
            return failures;
        }
    }
}

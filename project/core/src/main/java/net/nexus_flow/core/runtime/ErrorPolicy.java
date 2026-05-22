package net.nexus_flow.core.runtime;

import java.util.Objects;
import java.util.function.Predicate;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowError;

/**
 * Policy that drives how the runtime propagates failures observed during a dispatch.
 *
 * <p>The interface ships the type and its factories. The runtime wiring — fail-fast scope shutdown,
 * partial-failure aggregation, boundary isolation — is consumed by the structured dispatcher.
 *
 * <p>Variants:
 *
 * <ul>
 * <li>{@link FailFast} — first failure cancels every sibling fork.
 * <li>{@link CollectFailures} — every fork runs to completion; the runtime aggregates all
 * failures into a {@code DispatchResult.PartialFailure} or a {@link FlowError.Aggregated} .
 * <li>{@link IgnoreFailures} — failures matching {@code predicate} are discarded; everything else
 * propagates as in {@link FailFast}.
 * <li>{@link IsolatePerBoundary} — confines {@code inner} to a sub-scope so failures inside the
 * boundary do not propagate to the outer dispatch. Boundaries cannot nest by construction —
 * see the constructor's contract.
 * </ul>
 */
public sealed interface ErrorPolicy
        permits ErrorPolicy.FailFast,
        ErrorPolicy.CollectFailures,
        ErrorPolicy.IgnoreFailures,
        ErrorPolicy.IsolatePerBoundary {

    /**
     * Singleton accessor for {@link FailFast}.
     *
     * @return the singleton {@link FailFast} instance
     */
    static FailFast failFast() {
        return FailFast.INSTANCE;
    }

    /**
     * Singleton accessor for {@link CollectFailures}.
     *
     * @return the singleton {@link CollectFailures} instance
     */
    static CollectFailures collectFailures() {
        return CollectFailures.INSTANCE;
    }

    /**
     * Build an {@link IgnoreFailures} policy. The predicate is mandatory.
     *
     * @param predicate non-null test applied to each failure; failures for which the predicate
     *                  returns {@code true} are silently dropped, all others propagate as if {@link FailFast} were
     *                  active
     * @return a new {@link IgnoreFailures} wrapping {@code predicate}
     * @throws NullPointerException if {@code predicate} is {@code null}
     */
    static IgnoreFailures ignore(Predicate<Throwable> predicate) {
        return new IgnoreFailures(predicate);
    }

    /**
     * Wrap {@code inner} into an {@link IsolatePerBoundary}. {@code inner} must not itself be an
     * {@link IsolatePerBoundary}: a boundary is a one-level scope confine, not a recursive operator.
     *
     * @param inner the policy applied inside the boundary; must not be {@code null} and must not
     *              itself be an {@link IsolatePerBoundary}
     * @return a new {@link IsolatePerBoundary} wrapping {@code inner}
     * @throws NullPointerException     if {@code inner} is {@code null}
     * @throws IllegalArgumentException if {@code inner} is an {@link IsolatePerBoundary}
     */
    static IsolatePerBoundary isolate(ErrorPolicy inner) {
        return new IsolatePerBoundary(inner);
    }

    /**
     * Specification of fail-fast error handling. When the first failure occurs, it cancels the
     * surrounding scope; remaining forks observe the cancellation through their {@link
     * CancellationToken}. Other failures arriving concurrently are attached as suppressed on the
     * winning cause.
     */
    final class FailFast implements ErrorPolicy {
        static final FailFast INSTANCE = new FailFast();

        private FailFast() {
        }

        @Override
        public String toString() {
            return "ErrorPolicy.FailFast";
        }
    }

    /**
     * Specification of collect-failures error handling. Every fork runs to completion; failures are
     * returned as a {@link DispatchResult.PartialFailure} (when a value is also available) or wrapped
     * into a {@link FlowError.Aggregated}.
     */
    final class CollectFailures implements ErrorPolicy {
        static final CollectFailures INSTANCE = new CollectFailures();

        private CollectFailures() {
        }

        @Override
        public String toString() {
            return "ErrorPolicy.CollectFailures";
        }
    }

    /**
     * Specification of ignore-failures error handling. Failures matching {@link #predicate()} are
     * discarded by the runtime; every other failure propagates as if {@link FailFast} were active.
     *
     * @param predicate non-null test applied to every failure; must be a pure function (no observable
     *                  side effects)
     */
    record IgnoreFailures(Predicate<Throwable> predicate) implements ErrorPolicy {
        public IgnoreFailures {
            Objects.requireNonNull(predicate, "predicate");
        }
    }

    /**
     * Specification of isolated-boundary error handling. This policy confines {@code inner} to a
     * child scope so failures tolerated by {@code inner} do not propagate to the outer dispatch.
     *
     * <p><strong>Boundaries do not nest.</strong> Wrapping an {@link IsolatePerBoundary} inside
     * another {@link IsolatePerBoundary} would silently flatten the topology; we reject it at
     * construction time so the misconfiguration surfaces immediately.
     */
    record IsolatePerBoundary(ErrorPolicy inner) implements ErrorPolicy {
        public IsolatePerBoundary {
            Objects.requireNonNull(inner, "inner");
            if (inner instanceof IsolatePerBoundary) {
                throw new IllegalArgumentException(
                        "IsolatePerBoundary cannot wrap another IsolatePerBoundary");
            }
        }
    }
}

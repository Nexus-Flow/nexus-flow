package net.nexus_flow.core.cqrs.query;

import java.util.concurrent.Callable;
import net.nexus_flow.core.cqrs.query.exceptions.QueryHandlerExecutionError;

/**
 * Adapter-facing SPI for circuit-breaker protection around query handler invocations.
 *
 * <p>A circuit breaker stops forwarding calls to a slow or failing handler after a configurable
 * failure-rate threshold is crossed (CLOSED → OPEN), and automatically recovers after a wait
 * duration (OPEN → HALF_OPEN → CLOSED).
 *
 * <p>The framework ships NO in-core implementation: the small in-process surface a typical
 * deployment needs is best provided by a mature library (Resilience4j, Failsafe, or a host-
 * specific equivalent) wired in through an adapter module. {@link #execute(Callable)} is the single
 * contract the query bus uses; {@link #state()} is purely observational and surfaces to health
 * checks / dashboards.
 *
 * <p>A trivial no-op implementation that just runs the callable as-is satisfies the contract for
 * deployments that do not want the protection — see {@link #passThrough()}.
 */
@FunctionalInterface
public interface QueryCircuitBreaker {

    /**
     * Executes the given callable through the circuit breaker.
     *
     * @param <R>      callable result type
     * @param callable operation to protect
     * @return callable result
     * @throws QueryHandlerExecutionError if the circuit is open or the call fails
     */
    <R> R execute(Callable<R> callable) throws QueryHandlerExecutionError;

    /**
     * Returns the current state of the circuit breaker. Default implementation returns {@link
     * State#CLOSED} — appropriate for breakers that do not track state (the no-op pass-through
     * breaker, in-memory probes, mock implementations in tests). Real library-backed implementations
     * (Resilience4j, Failsafe, …) override this to reflect the underlying state machine.
     *
     * @return current circuit-breaker state; never {@code null}
     */
    default State state() {
        return State.CLOSED;
    }

    /**
     * Returns a no-op breaker that runs the callable as-is and reports {@link State#CLOSED}. The
     * canonical pick for {@code QueryBus} wiring when the host opts out of circuit-breaker protection
     * — preferable to {@code null} (callers can rely on a non-null breaker and skip an extra null
     * check on the hot path).
     *
     * @return a stateless, allocation-free pass-through breaker
     */
    static QueryCircuitBreaker passThrough() {
        return new QueryCircuitBreaker() {
            @Override
            public <R> R execute(Callable<R> callable) throws QueryHandlerExecutionError {
                try {
                    return callable.call();
                } catch (QueryHandlerExecutionError e) {
                    throw e;
                } catch (Exception e) {
                    throw new QueryHandlerExecutionError(e);
                }
            }
        };
    }

    /** Circuit-breaker state model. */
    enum State {
        /** The breaker is passing calls through normally. */
        CLOSED,
        /** The breaker is short-circuiting calls because the protected dependency is unhealthy. */
        OPEN,
        /** The breaker is probing with limited traffic to see whether recovery succeeded. */
        HALF_OPEN
    }
}

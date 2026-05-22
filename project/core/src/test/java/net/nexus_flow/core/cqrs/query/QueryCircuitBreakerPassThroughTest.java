package net.nexus_flow.core.cqrs.query;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.Callable;
import net.nexus_flow.core.cqrs.query.exceptions.QueryHandlerExecutionError;
import org.junit.jupiter.api.Test;

/**
 * Pins the no-op {@link QueryCircuitBreaker#passThrough()} contract that replaced the original
 * {@code default state() throws UnsupportedOperationException} smell. {@code passThrough()} is the
 * canonical pick for QueryBus wiring when the host opts out of circuit-breaker protection;
 * preferring it over {@code null} lets the bus rely on a non-null breaker and skip a hot-path null
 * check.
 */
class QueryCircuitBreakerPassThroughTest {

    @Test
    void passThrough_runsCallable_andReturnsResultByIdentity() throws Exception {
        Object           payload = new Object();
        Callable<Object> c       = () -> payload;
        Object           result  = QueryCircuitBreaker.passThrough().execute(c);
        assertSame(payload, result, "pass-through breaker must return the callable's result verbatim");
    }

    @Test
    void passThrough_reportsClosed() {
        assertEquals(
                     QueryCircuitBreaker.State.CLOSED,
                     QueryCircuitBreaker.passThrough().state(),
                     "pass-through breaker is, by definition, always passing calls through — state() is CLOSED");
    }

    @Test
    void anyBreakerInstance_defaultState_isClosed() {
        QueryCircuitBreaker breaker =
                new QueryCircuitBreaker() {
                    @Override
                    public <R> R execute(Callable<R> callable) {
                        throw new AssertionError("execute not invoked by this test");
                    }
                };
        assertEquals(
                     QueryCircuitBreaker.State.CLOSED,
                     breaker.state(),
                     "default state() implementation returns CLOSED — the original UnsupportedOperationException"
                             + " smell on a default SPI method is gone");
    }

    @Test
    void passThrough_rethrowsQueryHandlerExecutionErrorByIdentity() {
        QueryHandlerExecutionError original =
                new QueryHandlerExecutionError(new RuntimeException("inner"));
        QueryHandlerExecutionError thrown   =
                assertThrows(
                             QueryHandlerExecutionError.class,
                             () -> QueryCircuitBreaker.passThrough()
                                     .execute(
                                              () -> {
                                                  throw original;
                                              }));
        assertSame(
                   original,
                   thrown,
                   "QueryHandlerExecutionError is the framework's own error type and must propagate"
                           + " by-identity, never re-wrapped");
    }

    @Test
    void passThrough_wrapsCheckedExceptionInQueryHandlerExecutionError() {
        IOException                original = new IOException("io boom");
        QueryHandlerExecutionError thrown   =
                assertThrows(
                             QueryHandlerExecutionError.class,
                             () -> QueryCircuitBreaker.passThrough()
                                     .execute(
                                              () -> {
                                                  throw original;
                                              }));
        assertSame(
                   original,
                   thrown.getCause(),
                   "checked exceptions thrown by the callable get wrapped — cause chain preserved");
        assertNotNull(thrown.getMessage());
    }
}

package net.nexus_flow.core.cqrs.query;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Per-handler execution constraints for {@link QueryBus#ask(Query)} calls.
 *
 * <p>The {@link DefaultQueryBus} enforces {@link #timeout()} via {@code
 * CompletableFuture.orTimeout} and applies {@link #maxConcurrent()} as a bulkhead. When a query
 * executes inside an active {@link net.nexus_flow.core.runtime.ExecutionContext}, the default bus
 * additionally shortens the timeout to the remaining deadline so cooperative deadlines and
 * query-specific timeouts work together.
 *
 * <p>
 *
 * {@snippet :
 * // Query handler with a per-handler timeout
 * final class ProductQueryHandler extends AbstractQueryHandler<GetProductById, Product> {
 *     &#64;Override
 *     public QuerySettings settings() {
 *         return QuerySettings.withTimeout(Duration.ofSeconds(2));
 *     }
 * }
 * }
 *
 * @param timeout       maximum allowed duration for the query handler, or {@code null} for no explicit
 *                      timeout
 * @param maxConcurrent maximum concurrent executions for this handler, or {@code 0} for unlimited
 */
public record QuerySettings(@Nullable Duration timeout, int maxConcurrent) {

    /** Canonical no-constraint instance. */
    public static final QuerySettings NONE = new QuerySettings(null, 0);

    /**
     * Creates a settings object.
     *
     * @throws IllegalArgumentException if {@code timeout} is zero/negative or {@code maxConcurrent}
     *                                  is negative
     */
    public QuerySettings {
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
        }
        if (maxConcurrent < 0) {
            throw new IllegalArgumentException(
                    "maxConcurrent must be >= 0 (0=unlimited), got: " + maxConcurrent);
        }
    }

    /**
     * Creates timeout-only settings.
     *
     * @param timeout maximum allowed duration for the query handler; must be positive
     * @return settings with the supplied timeout and no concurrency limit
     * @throws NullPointerException if {@code timeout} is {@code null}
     */
    public static QuerySettings withTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        return new QuerySettings(timeout, 0);
    }

    /**
     * Creates concurrency-only settings.
     *
     * @param maxConcurrent maximum number of concurrent handler executions; must be at least {@code
     *     1}
     * @return settings with the supplied concurrency limit and no timeout
     * @throws IllegalArgumentException if {@code maxConcurrent < 1}
     */
    public static QuerySettings withMaxConcurrent(int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be >= 1");
        }
        return new QuerySettings(null, maxConcurrent);
    }

    /**
     * Creates settings with both timeout and concurrency constraints.
     *
     * @param timeout       maximum allowed duration for the query handler, or {@code null} for none
     * @param maxConcurrent maximum number of concurrent handler executions; {@code 0} means unlimited
     * @return new settings instance
     */
    public static QuerySettings of(Duration timeout, int maxConcurrent) {
        return new QuerySettings(timeout, maxConcurrent);
    }

    /**
     * Returns whether this handler has a concurrency bulkhead.
     *
     * @return {@code true} when {@link #maxConcurrent()} is greater than zero
     */
    public boolean hasConcurrencyLimit() {
        return maxConcurrent > 0;
    }

    /**
     * Returns the configured timeout if present.
     *
     * @return optional timeout value
     */
    public Optional<Duration> timeoutOptional() {
        return Optional.ofNullable(timeout);
    }
}

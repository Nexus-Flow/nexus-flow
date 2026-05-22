package net.nexus_flow.core.cqrs.query;

/**
 * Query-handler contract used by {@link AbstractQueryHandler} and adapter-specific wrappers.
 *
 * <p>The interface is intentionally public and open so framework integrations can decorate query
 * handlers without depending on package-private implementation details. Runtime registration still
 * happens through {@link AbstractQueryHandler} instances, which remain the canonical Nexus Flow
 * handler base class.
 *
 * @param <T> query payload type
 * @param <R> response type
 */
@FunctionalInterface
public interface QueryHandler<T extends Record, R> {

    /**
     * Handles the given query and returns a response.
     *
     * @param query the query to execute
     * @return query result
     */
    R handle(T query);

    /**
     * Returns the execution settings for this handler.
     *
     * @return handler settings, or {@link QuerySettings#NONE} when using defaults
     */
    default QuerySettings settings() {
        return QuerySettings.NONE;
    }
}

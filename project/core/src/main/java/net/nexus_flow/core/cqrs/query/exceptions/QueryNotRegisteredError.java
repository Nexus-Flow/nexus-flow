package net.nexus_flow.core.cqrs.query.exceptions;

import java.io.Serial;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.ddd.exceptions.DomainError;

/** Thrown when a query is dispatched without a registered handler for its exact body type. */
public final class QueryNotRegisteredError extends DomainError {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an error for an unregistered query.
     *
     * @param query query whose body type could not be resolved to a handler
     */
    public QueryNotRegisteredError(Query<?> query) {
        super(
              "query_not_registered_error",
              "No QueryHandler is registered for query: " + query.getBody().getClass().getSimpleName());
    }
}

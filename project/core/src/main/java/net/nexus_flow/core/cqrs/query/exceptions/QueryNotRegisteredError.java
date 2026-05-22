package net.nexus_flow.core.cqrs.query.exceptions;

import java.io.Serial;
import net.nexus_flow.core.cqrs.query.Query;

/**
 * Thrown when a query is dispatched without a registered handler for its exact body type.
 *
 * <p>This is a framework configuration error (the application wired up a dispatch without the
 * corresponding handler), not a modelled domain outcome. Stack-traceless because the diagnostic
 * value is in the query body type name carried by the message.
 */
public final class QueryNotRegisteredError extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an error for an unregistered query.
     *
     * @param query query whose body type could not be resolved to a handler
     */
    public QueryNotRegisteredError(Query<?> query) {
        super(
              "No QueryHandler is registered for query: " + query.getBody().getClass().getSimpleName(),
              null,
              /* enableSuppression= */ true,
              /* writableStackTrace= */ false);
    }
}

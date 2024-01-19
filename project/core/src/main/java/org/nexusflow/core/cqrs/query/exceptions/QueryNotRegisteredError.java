package org.nexusflow.core.cqrs.query.exceptions;

import org.nexusflow.core.cqrs.query.Query;
import org.nexusflow.core.ddd.exceptions.DomainError;

public final class QueryNotRegisteredError extends DomainError {
    public QueryNotRegisteredError(Query<?> query) {
        super("query_not_registered_error", String.format("The query <%s> hasn't a query handler associated", query.toString()));
    }
}

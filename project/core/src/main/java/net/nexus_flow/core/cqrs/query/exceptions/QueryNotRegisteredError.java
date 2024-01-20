package net.nexus_flow.core.cqrs.query.exceptions;

import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.ddd.exceptions.DomainError;

public final class QueryNotRegisteredError extends DomainError {
    public QueryNotRegisteredError(Query<?> query) {
        super("query_not_registered_error", String.format("The query <%s> hasn't a query handler associated", query.toString()));
    }
}

package com.nexus_flow.core.cqrs.domain.query.exceptions;

import com.nexus_flow.core.cqrs.domain.query.Query;

public final class QueryNotRegisteredError extends Exception {
    public QueryNotRegisteredError(Class<? extends Query> query) {
        super(String.format("The query <%s> hasn't a query handler associated", query.toString()));
    }
}

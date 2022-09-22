package com.nexus_flow.core.cqrs.domain.query.exceptions;

public final class QueryHandlerExecutionError extends RuntimeException {
    public QueryHandlerExecutionError(Throwable cause) {
        super(cause);
    }
}

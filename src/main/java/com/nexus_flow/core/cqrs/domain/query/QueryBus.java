package com.nexus_flow.core.cqrs.domain.query;

import com.nexus_flow.core.cqrs.domain.query.exceptions.QueryHandlerExecutionError;

public interface QueryBus {
    <R> R ask(Query query) throws QueryHandlerExecutionError;
}

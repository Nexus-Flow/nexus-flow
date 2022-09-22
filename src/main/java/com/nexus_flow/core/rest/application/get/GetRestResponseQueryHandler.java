package com.nexus_flow.core.rest.application.get;

import com.nexus_flow.core.cqrs.domain.annotations.NexusFlowQueryHandler;
import com.nexus_flow.core.cqrs.domain.query.QueryHandler;
import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.rest.domain.value_objects.PathVariable;

import java.util.List;

@NexusFlowQueryHandler
public final class GetRestResponseQueryHandler {

    private final GetRestResponseRequester requester;

    public GetRestResponseQueryHandler(GetRestResponseRequester finder) {
        this.requester = finder;
    }

    @QueryHandler
    public Response handle(GetRestResponseQuery query) {
        List<PathVariable> pathVariables = query.getPathVariableQueries().stream()
                .map(pathVariableQuery -> new PathVariable(pathVariableQuery.toMap()))
                .toList();

                return requester.get(pathVariables, query.getResponse(),query.getWithRetry());
    }
}

package com.nexus_flow.core.rest.application.post;

import com.nexus_flow.core.cqrs.domain.annotations.NexusFlowQueryHandler;
import com.nexus_flow.core.cqrs.domain.query.QueryHandler;
import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.rest.domain.value_objects.PathVariable;
import com.nexus_flow.core.rest.domain.value_objects.Request;

import java.util.List;

@NexusFlowQueryHandler
public final class PostRestResponseQueryHandler {

    private final PostRestResponseRequester requester;

    public PostRestResponseQueryHandler(PostRestResponseRequester requester) {
        this.requester = requester;
    }

    @QueryHandler
    public Response handle(PostRestResponseQuery query) {
        List<PathVariable> pathVariables = query.getPathVariableQueries().stream()
                .map(pathVariableQuery -> new PathVariable(pathVariableQuery.toMap()))
                .toList();

        Request request = new Request(query.getRequestQuery().getBody());

        return requester.post(pathVariables, request, query.getResponse(), query.getWithRetry());
    }
}

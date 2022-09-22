package com.nexus_flow.core.rest.application.post;

import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import com.nexus_flow.core.rest.domain.RestService;
import com.nexus_flow.core.rest.domain.value_objects.PathVariable;
import com.nexus_flow.core.rest.domain.value_objects.Request;

import java.util.List;

@NexusFlowService
public final class PostRestResponseRequester {

    private final RestService restService;

    public PostRestResponseRequester(RestService restService) {
        this.restService = restService;
    }

    public <T extends Response> T post(List<PathVariable> pathVariables, Request request, Class<? extends Response> response, Boolean withRetry) {
        return Boolean.TRUE.equals(withRetry) ? (T) restService.postWithRetry(pathVariables, request, response)
                : (T) restService.post(pathVariables, request, response);
    }
}

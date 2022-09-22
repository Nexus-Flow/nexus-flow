package com.nexus_flow.core.rest.application.get;

import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import com.nexus_flow.core.rest.domain.RestService;
import com.nexus_flow.core.rest.domain.value_objects.PathVariable;

import java.util.List;

@NexusFlowService
public final class GetRestResponseRequester {

    private final RestService restService;

    public GetRestResponseRequester(RestService restService) {
        this.restService = restService;
    }

    public <T extends Response> T get(List<PathVariable> pathVariables, Class<? extends Response> response, Boolean withRetry) {
        return Boolean.TRUE.equals(withRetry) ? (T) restService.getWithRetry(pathVariables, response)
                : (T) restService.get(pathVariables, response);
    }
}

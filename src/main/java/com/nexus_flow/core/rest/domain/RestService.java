package com.nexus_flow.core.rest.domain;

import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.rest.domain.value_objects.PathVariable;
import com.nexus_flow.core.rest.domain.value_objects.Request;

import java.util.List;

public interface RestService {

    <T extends Response> T get(List<PathVariable> pathVariables, Class<T> classType);

    <T extends Response> T getWithRetry(List<PathVariable> pathVariables, Class<T> classType);

    <T extends Response> T post(List<PathVariable> pathVariables, Request request, Class<T> classType);

    <T extends Response> T postWithRetry(List<PathVariable> pathVariables, Request request, Class<T> classType);

    BearerTokenProvider getBearerTokenProvider();
}

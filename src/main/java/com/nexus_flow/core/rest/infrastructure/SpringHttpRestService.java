package com.nexus_flow.core.rest.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import com.nexus_flow.core.rest.domain.BearerTokenProvider;
import com.nexus_flow.core.rest.domain.RestService;
import com.nexus_flow.core.rest.domain.exceptions.HttpResponseError;
import com.nexus_flow.core.rest.domain.exceptions.RestServiceError;
import com.nexus_flow.core.rest.domain.value_objects.PathVariable;
import com.nexus_flow.core.rest.domain.value_objects.Request;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Slf4j
@NexusFlowService
public class SpringHttpRestService implements RestService {

    private final HttpResponseEndpointDictionary endpointDictionary;
    private final BearerTokenProvider            bearerTokenProvider;
    private       RestTemplate                   restTemplate;

    public SpringHttpRestService(HttpResponseEndpointDictionary endpointDictionary, ApplicationContext context) {
        this.endpointDictionary  = endpointDictionary;
        this.bearerTokenProvider = retrieveBearerTokenFromContext(context);
        this.restTemplate        = restTemplate();
    }

    private BearerTokenProvider retrieveBearerTokenFromContext(ApplicationContext context) {
        try {
            return context.getBean(BearerTokenProvider.class);
        } catch (BeansException e) {
            return () -> "";
        }
    }

    @Override
    public <T extends Response> T get(List<PathVariable> pathVariables, Class<T> classType) {
        return getCall(pathVariables, classType);
    }

    @Override
    @Retryable(maxAttempts = 5, backoff = @Backoff(10000L))
    public <T extends Response> T getWithRetry(List<PathVariable> pathVariables, Class<T> classType) {
        return getCall(pathVariables, classType);
    }

    private <T extends Response> T getCall(List<PathVariable> pathVariables, Class<T> classType) {
        T response = null;

        HttpEntity<String> entity = new HttpEntity<>("", headersWithBearerToken());
        ResponseEntity<T>  httpResponse;
        try {
            httpResponse = this.restTemplate.exchange(resolveEndpoint(classType, pathVariables), HttpMethod.GET, entity, classType);
            if (httpResponse.getStatusCode().is2xxSuccessful()) {
                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                response = mapper.convertValue(httpResponse.getBody(), classType);
            }
        } catch (HttpClientErrorException e) {
            throw new HttpResponseError(e.getMessage());
        } catch (Exception e) {
            throw new RestServiceError(e.getCause().getMessage());
        }

        if (response == null) throw new RestServiceError("There where no response");

        return response;
    }

    @Override
    public <T extends Response> T post(List<PathVariable> pathVariables, Request request, Class<T> classType) {
        return postCall(pathVariables, request, classType);
    }

    @Override
    @Retryable(maxAttempts = 5, backoff = @Backoff(10000L))
    public <T extends Response> T postWithRetry(List<PathVariable> pathVariables, Request request, Class<T> classType) {
        return postCall(pathVariables, request, classType);
    }

    private <T extends Response> T postCall(List<PathVariable> pathVariables, Request request, Class<T> classType) {
        T response = null;

        HttpHeaders headers = headersWithBearerToken();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(Utils.jsonEncode(request.getBody()), headers);
        ResponseEntity<T>  httpResponse;
        try {
            httpResponse = this.restTemplate.exchange(resolveEndpoint(classType, pathVariables), HttpMethod.POST, entity, classType);
            if (httpResponse.getStatusCode().is2xxSuccessful()) {
                ObjectMapper mapper = new ObjectMapper();
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                response = mapper.convertValue(httpResponse.getBody(), classType);
            }
        } catch (HttpClientErrorException e) {
            throw new HttpResponseError(e.getMessage());
        } catch (Exception e) {
            throw new RestServiceError(e.getCause().getMessage());
        }

        if (response == null) throw new RestServiceError("There where no response");

        return response;
    }

    @Override
    public BearerTokenProvider getBearerTokenProvider() {
        return this.bearerTokenProvider;
    }

    private HttpHeaders headersWithBearerToken() {
        HttpHeaders headers = new HttpHeaders();
        String      token   = getBearerTokenProvider().getBearerToken();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private <T extends Response> String resolveEndpoint(Class<T> classType, List<PathVariable> pathVariables) {
        String endpoint = endpointDictionary.search(classType);
        if (endpoint.contains("{") && endpoint.contains("}")) {
            for (PathVariable pathVariable : pathVariables) {
                endpoint = endpoint.replace(
                        "{" + pathVariable.getName().getValue() + "}",
                        pathVariable.getValue().getValue());
            }
        }
        return endpoint;
    }


    private RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // Recovers converter
        MappingJackson2HttpMessageConverter httpMessageConverter1 = (MappingJackson2HttpMessageConverter) restTemplate
                .getMessageConverters().stream()
                .filter(httpMessageConverter -> httpMessageConverter instanceof MappingJackson2HttpMessageConverter)
                .findFirst().orElse(new MappingJackson2HttpMessageConverter());

        // Configures object mapper of the converter
        httpMessageConverter1.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return restTemplate;
    }

}

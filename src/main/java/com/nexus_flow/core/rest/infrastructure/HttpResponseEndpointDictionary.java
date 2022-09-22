package com.nexus_flow.core.rest.infrastructure;

import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.rest.domain.FromEndpoint;
import com.nexus_flow.core.rest.domain.exceptions.RestServiceError;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.reflections.Reflections;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
@ConfigurationProperties(prefix = "nexus-flow")
@Data
public class HttpResponseEndpointDictionary {

    private Map<Class<? extends Response>, String> endpointDictionary;
    private Map<String, String>                    services;

    public HttpResponseEndpointDictionary() {

    }

    @PostConstruct
    public void buildDictionary() {
        Reflections                    reflections = new Reflections("com.nexus_flow");
        Set<Class<? extends Response>> classes     = reflections.getSubTypesOf(Response.class);
        endpointDictionary = buildDictionary(classes);
    }


    public String search(Class<? extends Response> responseClass) throws RestServiceError {
        String endpoint = endpointDictionary.get(responseClass);
        if (endpoint == null) {
            throw new RestServiceError("There is not any " + responseClass.getSimpleName() + " registered");
        }

        return endpoint;
    }

    private Map<Class<? extends Response>, String> buildDictionary(Set<Class<? extends Response>> responses) {

        Map<Class<? extends Response>, String> dictionary = new HashMap<>();

        for (Class<? extends Response> response : responses) {
            if (response.isAnnotationPresent(FromEndpoint.class)) {

                FromEndpoint annotation = response.getAnnotation(FromEndpoint.class);
                dictionary.put(response, this.services.get(annotation.service()) + annotation.resource());
            }
        }

        return dictionary;
    }
}

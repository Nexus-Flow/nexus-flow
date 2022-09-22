package com.nexus_flow.core.rest.domain.exceptions;


import com.nexus_flow.core.ddd.exceptions.DomainError;

public final class HttpResponseError extends DomainError {
    public HttpResponseError(String message) {
        super("http_response_error", "This was the message to this client: " + message);
    }

}

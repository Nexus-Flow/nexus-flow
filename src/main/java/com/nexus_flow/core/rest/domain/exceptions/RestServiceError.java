package com.nexus_flow.core.rest.domain.exceptions;

import com.nexus_flow.core.ddd.exceptions.DomainError;

public final class RestServiceError extends DomainError {
    public RestServiceError(String message) {
        super("rest_service_error", message);
    }
}

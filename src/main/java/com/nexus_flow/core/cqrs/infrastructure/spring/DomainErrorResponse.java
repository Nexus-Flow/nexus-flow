package com.nexus_flow.core.cqrs.infrastructure.spring;

import com.nexus_flow.core.ddd.exceptions.DomainError;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
public record DomainErrorResponse(String errorCode, String errorMessage) {

    public static DomainErrorResponse fromDomainError(String controllerName, DomainError domainError) {
        log.error(controllerName + domainError.getErrorCode() + " - " + domainError.getMessage());
        return new DomainErrorResponse(domainError.getErrorCode(), domainError.getErrorMessage());
    }

    public static DomainErrorResponse fromSystemException(String controllerName,
                                                          String errorCode,
                                                          Exception exception) {
        log.error(controllerName + errorCode + " - " + exception.getMessage());
        return new DomainErrorResponse(errorCode, exception.getMessage());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DomainErrorResponse that = (DomainErrorResponse) o;
        return Objects.equals(errorCode, that.errorCode) && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(errorCode, errorMessage);
    }
}


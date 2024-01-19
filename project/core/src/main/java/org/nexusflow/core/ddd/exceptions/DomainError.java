package org.nexusflow.core.ddd.exceptions;

public abstract class DomainError extends RuntimeException {

    private final String errorCode;
    private final String errorMessage;

    protected DomainError(String errorCode, String errorMessage) {
        super(errorMessage);

        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}


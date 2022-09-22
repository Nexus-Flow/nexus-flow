package com.nexus_flow.core.ddd.app.controller;

import com.nexus_flow.core.cqrs.infrastructure.spring.DomainErrorResponse;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import com.nexus_flow.core.ddd.exceptions.WrongState;
import com.nexus_flow.core.rest.domain.exceptions.HttpResponseError;
import com.nexus_flow.core.rest.domain.exceptions.RestServiceError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

@Slf4j
@ControllerAdvice
@ResponseBody
public class SharedErrorHandlingController {

    public static final String CONTROLLER_NAME = "Shared ErrorHandlingController - ";

    @ExceptionHandler(WrongFormat.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public DomainErrorResponse wrongFormatExceptionHandler(WrongFormat e) {
        return DomainErrorResponse.fromDomainError(CONTROLLER_NAME, e);
    }

    @ExceptionHandler(WrongState.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public DomainErrorResponse wrongStateExceptionHandler(WrongFormat e) {
        return DomainErrorResponse.fromDomainError(CONTROLLER_NAME, e);
    }

    @ExceptionHandler(NoSuchMethodException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public DomainErrorResponse notNoSuchMethodExceptionHandler(NoSuchMethodException e) {
        return DomainErrorResponse.fromSystemException(CONTROLLER_NAME, "not_such_method", e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public DomainErrorResponse notValidRequestBody(MethodArgumentNotValidException e) {
        return DomainErrorResponse.fromSystemException(CONTROLLER_NAME, "not_valid_request_body", e);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public DomainErrorResponse genericRuntimeExceptionHandler(Exception e) {
        return DomainErrorResponse.fromSystemException(CONTROLLER_NAME, "general_exception", e);
    }

    @ExceptionHandler(HttpResponseError.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public DomainErrorResponse genericRuntimeExceptionHandler(HttpResponseError e) {
        return DomainErrorResponse.fromDomainError(CONTROLLER_NAME, e);
    }

    @ExceptionHandler(RestServiceError.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public DomainErrorResponse genericRuntimeExceptionHandler(RestServiceError e) {
        return DomainErrorResponse.fromDomainError(CONTROLLER_NAME, e);
    }

}




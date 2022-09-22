package com.nexus_flow.core.criteria.domain.filter;


import com.nexus_flow.core.ddd.exceptions.DomainError;

public class InvalidFilterValue extends DomainError {
    public InvalidFilterValue(String errorCode, String errorMessage) {
        super(errorCode, errorMessage);
    }
}

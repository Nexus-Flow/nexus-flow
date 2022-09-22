package com.nexus_flow.core.ddd.exceptions;


public abstract class DataInconsistency extends DomainError {
    public DataInconsistency(String msg) {
        super("data_inconsistency", msg);
    }
}

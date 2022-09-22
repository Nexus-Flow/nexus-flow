package com.nexus_flow.core.ddd.exceptions;

public class RetrievingEventFromDatabaseError extends RuntimeException {

    public RetrievingEventFromDatabaseError(Exception e) {
        super(" -----> " + e.getCause().toString());
    }

    public RetrievingEventFromDatabaseError(Throwable e) {
        super(" -----> " + e.getCause().toString());
    }
    
}

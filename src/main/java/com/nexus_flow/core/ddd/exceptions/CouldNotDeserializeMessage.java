package com.nexus_flow.core.ddd.exceptions;

public class CouldNotDeserializeMessage extends RuntimeException {
    public CouldNotDeserializeMessage(String e) {
        super(e);
    }
}

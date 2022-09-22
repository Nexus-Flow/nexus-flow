package com.nexus_flow.core.ddd.exceptions;

public class CouldNotDeserializeBody extends RuntimeException {
    public CouldNotDeserializeBody(String e) {
        super(e);
    }
}

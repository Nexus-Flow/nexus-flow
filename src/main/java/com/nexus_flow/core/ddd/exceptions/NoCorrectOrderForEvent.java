package com.nexus_flow.core.ddd.exceptions;

public class NoCorrectOrderForEvent extends RuntimeException {
    public NoCorrectOrderForEvent(String message) {
        super(message);
    }
}

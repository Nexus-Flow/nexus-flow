package com.nexus_flow.core.ddd.exceptions;


public class WrongState extends DomainError {
    public WrongState(String message) {
        super("wrong_state", "");
    }
}

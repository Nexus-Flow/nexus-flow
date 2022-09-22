package com.nexus_flow.core.messaging.domain;

public class NoSubscribersRegistered extends RuntimeException {
    public NoSubscribersRegistered(String e) {
        super(e);
    }
}

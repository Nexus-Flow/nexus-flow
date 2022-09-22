package com.nexus_flow.core.cqrs.domain.command;

public interface CommandConsumer {

    void consume();

    void stop();

}

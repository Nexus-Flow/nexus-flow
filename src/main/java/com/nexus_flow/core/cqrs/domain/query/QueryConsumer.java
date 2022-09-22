package com.nexus_flow.core.cqrs.domain.query;

public interface QueryConsumer {

    void consume();

    void stop();

}

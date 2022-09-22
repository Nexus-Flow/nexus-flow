package com.nexus_flow.core.messaging.domain;

public interface DomainEventsConsumer {

    void consume();

    void stop();

}

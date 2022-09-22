package com.nexus_flow.core.messaging.domain;

import java.util.List;

public interface EventBus {
    void publish(final List<DomainEvent> events);
}

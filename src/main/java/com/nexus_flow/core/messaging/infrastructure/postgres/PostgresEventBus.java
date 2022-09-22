package com.nexus_flow.core.messaging.infrastructure.postgres;

import com.nexus_flow.core.cqrs.domain.annotations.NexusFlowBus;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.messaging.domain.EventBus;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@NexusFlowBus
public final class PostgresEventBus implements EventBus {

    private final DomainEventMapper                 domainEventMapper;
    private final SpringDataJpaDomainEventInterface springDataJpaDomainEventInterface;

    public PostgresEventBus(DomainEventMapper domainEventMapper,
                            SpringDataJpaDomainEventInterface springDataJpaDomainEventInterface) {
        this.domainEventMapper                 = domainEventMapper;
        this.springDataJpaDomainEventInterface = springDataJpaDomainEventInterface;
    }


    @Override
    public void publish(List<DomainEvent> events) {
        springDataJpaDomainEventInterface.saveAll(domainEventMapper.toEntity(events));
        springDataJpaDomainEventInterface.flush();

        log.info("{} events were persisted/will remain in the database", events.size());
    }


}

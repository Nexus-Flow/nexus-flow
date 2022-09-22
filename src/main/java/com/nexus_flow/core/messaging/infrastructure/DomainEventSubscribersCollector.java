package com.nexus_flow.core.messaging.infrastructure;

import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import com.nexus_flow.core.messaging.domain.DomainEventSubscriber;
import org.reflections.Reflections;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;

@NexusFlowService
public final class DomainEventSubscribersCollector {

    HashMap<Class<?>, DomainEventSubscriberData> domainEventSubscriberMap;

    public DomainEventSubscribersCollector(HashMap<Class<?>, DomainEventSubscriberData> domainEventSubscriberMap) {
        this.domainEventSubscriberMap = domainEventSubscriberMap;
    }

    public DomainEventSubscribersCollector() {
        this(scanDomainEventSubscribers());
    }

    private static HashMap<Class<?>, DomainEventSubscriberData> scanDomainEventSubscribers() {
        Reflections   reflections = new Reflections("com.nexus_flow");
        Set<Class<?>> subscribers = reflections.getTypesAnnotatedWith(DomainEventSubscriber.class);

        HashMap<Class<?>, DomainEventSubscriberData> subscribersInformation = new HashMap<>();

        for (Class<?> subscriberClass : subscribers) {

            DomainEventSubscriber annotation = subscriberClass.getAnnotation(DomainEventSubscriber.class);

            subscribersInformation.put(
                    subscriberClass,
                    new DomainEventSubscriberData(subscriberClass, Arrays.asList(annotation.value()))
            );
        }

        return subscribersInformation;
    }

    public Collection<DomainEventSubscriberData> all() {
        return domainEventSubscriberMap.values();
    }

    public String[] rabbitMqFormattedNames() {
        return domainEventSubscriberMap.values()
                .stream()
                .map(DomainEventSubscriberData::formatRabbitMqQueueName)
                .distinct()
                .toArray(String[]::new);
    }
}

package com.nexus_flow.core.messaging.infrastructure;

import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import org.reflections.Reflections;
import org.springframework.amqp.core.TopicExchange;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@NexusFlowService
public final class DomainEventsCollector {

    HashMap<String, Class<? extends DomainEvent>> domainEventsMap;

    public DomainEventsCollector() {
        Reflections                       reflections = new Reflections("com.nexus_flow");
        Set<Class<? extends DomainEvent>> classes     = reflections.getSubTypesOf(DomainEvent.class);

        classes.removeIf(aClass -> Modifier.isAbstract(aClass.getModifiers()));


        try {
            domainEventsMap = formatEvents(classes);
        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    public Class<? extends DomainEvent> forName(String name) {
        return domainEventsMap.get(name);
    }

    public String forClass(Class<? extends DomainEvent> domainEventClass) {
        return domainEventsMap.entrySet()
                .stream()
                .filter(entry -> Objects.equals(entry.getValue(), domainEventClass))
                .map(Map.Entry::getKey)
                .findFirst().orElse("");
    }

    private HashMap<String, Class<? extends DomainEvent>> formatEvents(Set<Class<? extends DomainEvent>> domainEvents)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {

        HashMap<String, Class<? extends DomainEvent>> events = new HashMap<>();

        for (Class<? extends DomainEvent> domainEvent : domainEvents) {

            DomainEvent nullInstance = domainEvent.getConstructor().newInstance();

            events.put((String) domainEvent.getMethod("getEventName").invoke(nullInstance), domainEvent);
        }

        return events;
    }

    public TopicExchange getSourceExchange(String eventName) {
        String contextName = eventName.split("\\.")[1];
        return new TopicExchange(contextName);
    }
}

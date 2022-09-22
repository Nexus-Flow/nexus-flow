package com.nexus_flow.core.messaging.infrastructure;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import com.nexus_flow.core.ddd.exceptions.CouldNotDeserializeBody;
import com.nexus_flow.core.ddd.exceptions.CouldNotDeserializeMessage;
import com.nexus_flow.core.messaging.domain.DomainEvent;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Map;

@NexusFlowService
public final class DomainEventJsonDeserializer {

    private final DomainEventsCollector domainEventsCollector;

    public DomainEventJsonDeserializer(DomainEventsCollector domainEventsCollector) {
        this.domainEventsCollector = domainEventsCollector;
    }

    public DomainEvent deserialize(String body) throws CouldNotDeserializeMessage {

        DomainEvent nullInstance;
        Object      domainEvent;

        try {
            Map<String, Serializable>    eventData        = Utils.jsonDecode(body);
            Map<String, Serializable>    data             = Utils.toMapStringSerializable(eventData.get("data"));
            Map<String, Serializable>    meta             = Utils.toMapStringSerializable(eventData.get("meta"));
            Map<String, Serializable>    attributes       = Utils.toMapStringSerializable(data.get("attributes"));
            Class<? extends DomainEvent> domainEventClass = domainEventsCollector.forName((String) data.get("type"));


            nullInstance = domainEventClass.getConstructor().newInstance();

            Method fromPrimitivesMethod = domainEventClass.getMethod(
                    "fromPrimitives",
                    String.class,
                    String.class,
                    String.class,
                    Integer.class,
                    Map.class
            );

            domainEvent = fromPrimitivesMethod.invoke(
                    nullInstance,
                    attributes.get("id").toString(),
                    data.get("id").toString(),
                    data.get("occurred_on").toString(),
                    Integer.valueOf(meta.get("times_tried_to_publish").toString()),
                    attributes
            );

        } catch (CouldNotDeserializeBody couldNotDeserializeBody) {
            throw new CouldNotDeserializeMessage(couldNotDeserializeBody.getMessage());
        } catch (Exception e) {
            throw new CouldNotDeserializeMessage(e.getCause().toString());
        }


        return (DomainEvent) domainEvent;
    }
}

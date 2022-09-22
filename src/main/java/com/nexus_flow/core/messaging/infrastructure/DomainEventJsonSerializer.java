package com.nexus_flow.core.messaging.infrastructure;


import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.messaging.domain.DomainEvent;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public final class DomainEventJsonSerializer {

    public static String serialize(DomainEvent domainEvent) {

        Map<String, Serializable> attributes = domainEvent.toPrimitives();

        attributes.put("id", domainEvent.getAggregateId());

        return Utils.jsonEncode(new HashMap<>() {{
            put("data", new HashMap<String, Serializable>() {{
                put("id", domainEvent.getEventId());
                put("type", domainEvent.getEventName());
                put("occurred_on", domainEvent.getOccurredOn());
                put("attributes", (Serializable) attributes);
            }});
            put("meta", new HashMap<String, Serializable>() {{
                put("times_tried_to_publish", domainEvent.getTimesWasTriedToPublish());
            }});
        }});
    }
}

package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.cqrs.reflection.TypeReference;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;


public abstract class EventTypeSignature<T> {
    private final TypeReference<T> eventType;

    protected EventTypeSignature() {
        this.eventType = initializeEventType();
    }

    private TypeReference<T> initializeEventType() {
        Type superClass = this.getClass().getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType parameterizedType)) {
            throw new RuntimeException("EventTypeSignature must be parameterized with concrete classes.");
        }
        Type[] types = parameterizedType.getActualTypeArguments();
        if (types.length == 0) {
            throw new RuntimeException("The type parameters should not be empty.");
        }
        return new TypeReference<>(types[0]);
    }

    public final TypeReference<T> getEventType() {
        return eventType;
    }

}


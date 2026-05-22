package net.nexus_flow.core.cqrs.event;

import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.Consumer;
import net.nexus_flow.core.cqrs.introspection.EventListenerRegistration;
import net.nexus_flow.core.cqrs.introspection.HandlerMethodIntrospector;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.types.TypeReference;

/**
 * Adapter for creating event listener registrations from Java reflection methods.
 *
 * <p>This utility class provides a static factory to wrap a target object and a reflective method
 * as a {@link DomainEventListener}, handling invocation and error propagation. Used internally by
 * the event bus to register annotated handler methods.
 */
final class DomainEventListenerMethodAdapter {

    private DomainEventListenerMethodAdapter() {
        throw new AssertionError("No instances of DomainEventListenerMethodAdapter");
    }

    static EventListenerRegistration fromMethod(
            Object target, Method method, EventListenerOptions<?> options) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(options, "options");

        TypeReference<DomainEvent> eventType = HandlerMethodIntrospector.eventMessageType(method);

        makeListenerMethodAccessible(target, method);

        Consumer<DomainEvent>            consumer = event -> invoke(target, method, event);
        DomainEventListener<DomainEvent> listener =
                new DomainEventListenerDsl.InlineEventListener<>(eventType, consumer, options);
        return EventListenerRegistration.of(listener, eventType);
    }

    @SuppressWarnings(
        "java:S3011") // Intentional framework behavior: allows annotated non-public listener methods.
    private static void makeListenerMethodAccessible(Object target, Method method) {
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : target;

        if (method.canAccess(receiver)) {
            return;
        }

        try {
            method.setAccessible(true);
        } catch (InaccessibleObjectException | SecurityException e) {
            throw new IllegalArgumentException(
                    "Event listener method cannot be made accessible. "
                            + "Make the listener method public, or open the declaring package/module: "
                            + method,
                    e);
        }
    }

    private static void invoke(Object target, Method method, DomainEvent event) {
        try {
            method.invoke(target, event);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access listener method: " + method, e);
        } catch (InvocationTargetException e) {
            throw net.nexus_flow.core.runtime.reflect.ReflectiveInvocationPropagator.propagate(
                                                                                               "Listener method", method, e);
        }
    }
}

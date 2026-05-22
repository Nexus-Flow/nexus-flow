package net.nexus_flow.core.cqrs.introspection;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.types.TypeReference;

/**
 * Public reflection primitive for framework integration layers that discover handler methods from
 * container annotations.
 *
 * <p>Spring runtime reflection, Quarkus build steps, and Micronaut annotation processors can use
 * this class to validate a candidate method such as {@code void place(PlaceOrder command)} and
 * recover the routing token that Nexus Flow uses internally without depending on package-private
 * handler implementations.
 */
public final class HandlerMethodIntrospector {

    private HandlerMethodIntrospector() {
        throw new AssertionError("No instances of HandlerMethodIntrospector");
    }

    /**
     * Returns the single message parameter type as a {@link TypeReference}.
     *
     * @param method candidate handler method
     * @return type token for the sole record or domain-event parameter
     * @throws IllegalArgumentException if the method does not have exactly one supported message
     *                                  parameter
     */
    @SuppressWarnings(
        "java:S1452") // Type token derived from reflection (mirrors Method.getGenericParameterTypes).
    public static TypeReference<?> messageType(Method method) {
        Type parameterType = singleMessageParameter(method);
        return new TypeReference<>(parameterType);
    }

    /**
     * Returns the single command/query record parameter as a type token.
     *
     * @param method candidate handler method
     * @return record parameter type token
     * @throws IllegalArgumentException if the method does not declare exactly one record parameter
     */
    public static TypeReference<Record> recordMessageType(Method method) {
        Type parameterType = singleRecordParameter(method);
        return new TypeReference<>(parameterType);
    }

    /**
     * Returns the single domain-event parameter as a type token.
     *
     * @param method candidate handler method
     * @return domain-event parameter type token
     * @throws IllegalArgumentException if the method does not declare exactly one domain-event
     *                                  parameter
     */
    public static TypeReference<DomainEvent> eventMessageType(Method method) {
        Type parameterType = singleDomainEventParameter(method);
        return new TypeReference<>(parameterType);
    }

    /**
     * Returns the raw command/query record class used by class-keyed registries.
     *
     * @param method candidate handler method
     * @return raw record class for routing
     * @throws IllegalArgumentException if the method does not declare exactly one record parameter
     */
    @SuppressWarnings({"unchecked", "java:S1452"
    }) // Type token; bound is the narrowest safe upper-bound for the routing key.
    public static Class<? extends Record> recordMessageClass(Method method) {
        return (Class<? extends Record>) rawClass(singleRecordParameter(method));
    }

    /**
     * Returns the raw domain-event class used by class-keyed registries.
     *
     * @param method candidate handler method
     * @return raw event class for routing
     * @throws IllegalArgumentException if the method does not declare exactly one domain-event
     *                                  parameter
     */
    @SuppressWarnings({"unchecked", "java:S1452"
    }) // Type token; bound is the narrowest safe upper-bound for the routing key.
    public static Class<? extends DomainEvent> eventMessageClass(Method method) {
        return (Class<? extends DomainEvent>) rawClass(singleDomainEventParameter(method));
    }

    /**
     * Returns the method return type, mapping {@code void} to {@link Void}.
     *
     * @param method candidate handler method
     * @return return type token, or {@link Void} when the method returns {@code void}
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @SuppressWarnings(
        "java:S1452") // Type token derived from reflection; Void.class encodes the void case.
    public static TypeReference<?> returnType(Method method) {
        Objects.requireNonNull(method, "method");
        if (isVoidReturn(method)) {
            return new TypeReference<>(Void.class);
        }
        return new TypeReference<>(method.getGenericReturnType());
    }

    /**
     * Returns whether the method has a {@code void} return.
     *
     * @param method candidate handler method
     * @return {@code true} when the method returns {@code void}
     * @throws NullPointerException if {@code method} is {@code null}
     */
    public static boolean isVoidReturn(Method method) {
        Objects.requireNonNull(method, "method");
        return method.getReturnType() == Void.TYPE;
    }

    /**
     * Validates and returns the single generic Nexus message parameter type.
     *
     * @param method candidate handler method
     * @return sole method parameter type
     * @throws IllegalArgumentException if the method does not declare exactly one supported message
     *                                  parameter
     */
    public static Type singleMessageParameter(Method method) {
        Type     parameterType = singleParameter(method);
        Class<?> raw           = rawClass(parameterType);
        if (!Record.class.isAssignableFrom(raw) && !DomainEvent.class.isAssignableFrom(raw)) {
            throw new IllegalArgumentException(
                    "Handler method parameter must be a Record or DomainEvent subtype: " + method);
        }
        return parameterType;
    }

    /**
     * Validates and returns the single command/query record parameter type.
     *
     * @param method candidate handler method
     * @return sole record parameter type
     * @throws IllegalArgumentException if the method does not declare exactly one record parameter
     */
    public static Type singleRecordParameter(Method method) {
        Type     parameterType = singleParameter(method);
        Class<?> raw           = rawClass(parameterType);
        if (!Record.class.isAssignableFrom(raw)) {
            throw new IllegalArgumentException(
                    "Handler method parameter must be a Record subtype: " + method);
        }
        return parameterType;
    }

    /**
     * Validates and returns the single domain-event parameter type.
     *
     * @param method candidate handler method
     * @return sole domain-event parameter type
     * @throws IllegalArgumentException if the method does not declare exactly one domain-event
     *                                  parameter
     */
    public static Type singleDomainEventParameter(Method method) {
        Type     parameterType = singleParameter(method);
        Class<?> raw           = rawClass(parameterType);
        if (!DomainEvent.class.isAssignableFrom(raw)) {
            throw new IllegalArgumentException(
                    "Handler method parameter must be a DomainEvent subtype: " + method);
        }
        return parameterType;
    }

    private static Type singleParameter(Method method) {
        Objects.requireNonNull(method, "method");
        Type[] parameters = method.getGenericParameterTypes();
        if (parameters.length != 1) {
            throw new IllegalArgumentException(
                    "Handler method must declare exactly one message parameter: " + method);
        }
        return parameters[0];
    }

    private static Class<?> rawClass(Type type) {
        return switch (type) {
            case Class<?> klass                                                                            -> klass;
            case ParameterizedType parameterized when parameterized.getRawType() instanceof Class<?> klass ->
                 klass;
            default                                                                                        ->
                    throw new IllegalArgumentException("Unsupported handler method parameter type: " + type);
        };
    }
}

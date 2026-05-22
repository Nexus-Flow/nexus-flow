package net.nexus_flow.core.cqrs.query;

import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.Function;
import net.nexus_flow.core.cqrs.introspection.HandlerMethodIntrospector;
import net.nexus_flow.core.cqrs.introspection.QueryHandlerRegistration;
import net.nexus_flow.core.types.TypeReference;

final class QueryHandlerMethodAdapter {

    private QueryHandlerMethodAdapter() {
        throw new AssertionError("No instances of QueryHandlerMethodAdapter");
    }

    @SuppressWarnings("unchecked")
    static QueryHandlerRegistration fromMethod(Object target, Method method, QuerySettings settings) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(settings, "settings");

        if (HandlerMethodIntrospector.isVoidReturn(method)) {
            throw new IllegalArgumentException("Query handler method must return a value: " + method);
        }

        TypeReference<Record> queryType  = HandlerMethodIntrospector.recordMessageType(method);
        TypeReference<Object> returnType =
                (TypeReference<Object>) HandlerMethodIntrospector.returnType(method);

        makeHandlerMethodAccessible(target, method);

        Function<Record, Object>             function = query -> invoke(target, method, query);
        AbstractQueryHandler<Record, Object> handler  =
                new QueryHandlerDsl.InlineQueryHandler<>(queryType, returnType, function, settings);
        return QueryHandlerRegistration.of(handler, queryType, returnType);
    }

    @SuppressWarnings(
        "java:S3011") // Intentional framework behavior: allows annotated non-public handler methods.
    private static void makeHandlerMethodAccessible(Object target, Method method) {
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : target;

        if (method.canAccess(receiver)) {
            return;
        }

        try {
            method.setAccessible(true);
        } catch (InaccessibleObjectException | SecurityException e) {
            throw new IllegalArgumentException(
                    "Query handler method cannot be made accessible. "
                            + "Make the handler method public, or open the declaring package/module: "
                            + method,
                    e);
        }
    }

    private static Object invoke(Object target, Method method, Record query) {
        try {
            return method.invoke(target, query);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access query handler method: " + method, e);
        } catch (InvocationTargetException e) {
            throw net.nexus_flow.core.runtime.reflect.ReflectiveInvocationPropagator.propagate(
                                                                                               "Query handler method", method, e);
        }
    }
}

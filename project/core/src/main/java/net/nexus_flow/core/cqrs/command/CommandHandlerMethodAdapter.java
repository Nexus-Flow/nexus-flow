package net.nexus_flow.core.cqrs.command;

import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.nexus_flow.core.cqrs.introspection.CommandHandlerRegistration;
import net.nexus_flow.core.cqrs.introspection.HandlerMethodIntrospector;
import net.nexus_flow.core.types.TypeReference;

final class CommandHandlerMethodAdapter {

    private CommandHandlerMethodAdapter() {
        throw new AssertionError("No instances of CommandHandlerMethodAdapter");
    }

    static CommandHandlerRegistration fromMethod(
            Object target, Method method, CommandHandlerOptions options) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(options, "options");

        TypeReference<Record> commandType = HandlerMethodIntrospector.recordMessageType(method);

        makeHandlerMethodAccessible(target, method);

        if (HandlerMethodIntrospector.isVoidReturn(method)) {
            Consumer<Record>               consumer = command -> invokeVoid(target, method, command);
            NoReturnCommandHandler<Record> handler  =
                    new CommandHandlerBuilder.NoReturnCommandHandlerImpl<>(consumer, commandType, options);
            return CommandHandlerRegistration.of(handler, commandType);
        }

        Function<Record, Object>             function = command -> invokeReturn(target, method, command);
        ReturnCommandHandler<Record, Object> handler  =
                new CommandHandlerBuilder.ReturnCommandHandlerImpl<>(function, commandType, options);
        return CommandHandlerRegistration.of(handler, commandType);
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
                    "Command handler method cannot be made accessible. "
                            + "Make the handler method public, or open the declaring package/module: "
                            + method,
                    e);
        }
    }

    private static void invokeVoid(Object target, Method method, Record command) {
        invokeReturn(target, method, command);
    }

    private static Object invokeReturn(Object target, Method method, Record command) {
        try {
            return method.invoke(target, command);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access handler method: " + method, e);
        } catch (InvocationTargetException e) {
            throw net.nexus_flow.core.runtime.reflect.ReflectiveInvocationPropagator.propagate(
                                                                                               "Handler method", method, e);
        }
    }
}

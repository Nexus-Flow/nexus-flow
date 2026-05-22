package net.nexus_flow.core.cqrs.introspection;

import java.util.Objects;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.cqrs.command.CommandHandler;
import net.nexus_flow.core.cqrs.command.NoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.ReturnCommandHandler;
import net.nexus_flow.core.types.TypeReference;

/**
 * Opaque registration token for command handlers discovered by IoC adapters.
 *
 * <p>Spring runtime reflection, Quarkus build-time indexing, Micronaut annotation processors, or
 * similar integrations can scan container beans for annotated methods, call {@code
 * CommandHandler.fromMethod(...)}, inspect the command routing metadata exposed here, and register
 * the handler without ever materializing a {@code CommandHandler<?, ?, ?>} reference in their own
 * public or internal API.
 */
public final class CommandHandlerRegistration {
    private final CommandHandler<?, ?, ?> handler;
    private final TypeReference<?>        commandType;
    private final boolean                 returnsValue;

    CommandHandlerRegistration(
            CommandHandler<?, ?, ?> handler, TypeReference<?> commandType, boolean returnsValue) {
        this.handler      = Objects.requireNonNull(handler, "handler");
        this.commandType  = Objects.requireNonNull(commandType, "commandType");
        this.returnsValue = returnsValue;
    }

    /**
     * Creates a token for a fire-and-forget command handler.
     *
     * @param <T>         command payload type
     * @param handler     concrete no-return command handler
     * @param commandType exact command routing key
     * @return immutable registration token
     */
    public static <T extends Record> CommandHandlerRegistration of(
            NoReturnCommandHandler<T> handler, TypeReference<T> commandType) {
        return new CommandHandlerRegistration(handler, commandType, false);
    }

    /**
     * Creates a token for a command handler that returns a value.
     *
     * @param <T>         command payload type
     * @param <R>         response type
     * @param handler     concrete return command handler
     * @param commandType exact command routing key
     * @return immutable registration token
     */
    public static <T extends Record, R> CommandHandlerRegistration of(
            ReturnCommandHandler<T, R> handler, TypeReference<T> commandType) {
        return new CommandHandlerRegistration(handler, commandType, true);
    }

    /**
     * Returns the command type used as the command-bus routing key.
     *
     * @return command routing type token
     */
    @SuppressWarnings(
        "java:S1452") // Type token (akin to Class<?>); routing key, not a generic container.
    public TypeReference<?> commandType() {
        return commandType;
    }

    /**
     * Returns whether the handler produces a response value.
     *
     * @return {@code true} for value-returning handlers, {@code false} for fire-and-forget handlers
     */
    public boolean returnsValue() {
        return returnsValue;
    }

    /**
     * Registers the wrapped handler on the given command bus.
     *
     * @param bus target command bus
     * @throws NullPointerException if {@code bus} is {@code null}
     */
    public void registerOn(CommandBus bus) {
        Objects.requireNonNull(bus, "bus");
        switch (handler) {
            case NoReturnCommandHandler<?> noReturn -> registerNoReturn(bus, noReturn);
            case ReturnCommandHandler<?, ?> returns -> registerReturn(bus, returns);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> void registerNoReturn(
            CommandBus bus, NoReturnCommandHandler<?> handler) {
        bus.register((NoReturnCommandHandler<T>) handler);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record, R> void registerReturn(
            CommandBus bus, ReturnCommandHandler<?, ?> handler) {
        bus.register((ReturnCommandHandler<T, R>) handler);
    }
}

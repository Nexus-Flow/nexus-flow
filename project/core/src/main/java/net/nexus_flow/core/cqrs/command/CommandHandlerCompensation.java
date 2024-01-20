package net.nexus_flow.core.cqrs.command;

@FunctionalInterface
public interface CommandHandlerCompensation<T> {
    void handleCompensation(T command);
}
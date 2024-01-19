package org.nexusflow.core.cqrs.command;

@FunctionalInterface
public interface CommandHandlerCompensation<T> {
    void handleCompensation(T command);
}
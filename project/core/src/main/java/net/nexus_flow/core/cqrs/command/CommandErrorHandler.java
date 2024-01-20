package net.nexus_flow.core.cqrs.command;

@FunctionalInterface
public interface CommandErrorHandler {
    void onError(Throwable e);

}
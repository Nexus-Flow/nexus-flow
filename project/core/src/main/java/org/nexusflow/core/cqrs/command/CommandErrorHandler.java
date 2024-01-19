package org.nexusflow.core.cqrs.command;

@FunctionalInterface
public interface CommandErrorHandler {
    void onError(Throwable e);

}
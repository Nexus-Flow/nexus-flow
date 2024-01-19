package org.nexusflow.core.cqrs.command;

import java.util.List;

public interface CommandHandlerExecutor<T extends Record, R, H extends CommandHandler<T, R, H>> {

    void execute(Command<T> command);

    void execute(List<Command<T>> command);

    R submitAndReturn(Command<T> command);

    List<R> submitAndReturn(List<Command<T>> command);

    void adjustConcurrency(int newConcurrencyLevel);

    void close();
}
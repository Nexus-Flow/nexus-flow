package org.nexusflow.core.cqrs.command;

import org.nexusflow.core.cqrs.command.exceptions.CommandHandlerExecutionError;

public sealed interface CommandBus permits DefaultCommandBus {

    static CommandBus getInstance() {
        return CommandBusFactory.getInstance();
    }

    <T extends Record> void register(NoReturnCommandHandler<T> handler);

    <T extends Record, R> void register(ReturnCommandHandler<T, R> handler);

    <T extends Record> void unregister(NoReturnCommandHandler<T> handler);

    <T extends Record, R> void unregister(ReturnCommandHandler<T, R> handler);

    <T extends Record> void dispatch(Command<T> command) throws CommandHandlerExecutionError;

    <T extends Record, R> R dispatchAndReturn(Command<T> command) throws CommandHandlerExecutionError;

    class CommandBusFactory {
        private static volatile CommandBus instance;

        private CommandBusFactory() {
        }

        public static CommandBus getInstance() {
            if (instance == null) {
                synchronized (CommandBusFactory.class) {
                    if (instance == null) {
                        instance = new DefaultCommandBus();
                    }
                }
            }
            return instance;
        }
    }
}
package net.nexus_flow.core.cqrs.command;

import net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError;

non-sealed class DefaultCommandBus implements CommandBus {

    private final CommandConsumerRegistry consumerRegistry = CommandConsumerRegistry.getInstance();

    @Override
    public <T extends Record> void register(NoReturnCommandHandler<T> handler) {
        if (handler instanceof AbstractNoReturnCommandHandler<T> handler1) {
            consumerRegistry.createPublisher(handler1.getCommandType(), handler);
        }
    }

    @Override
    public <T extends Record, R> void register(ReturnCommandHandler<T, R> handler) {
        if (handler instanceof AbstractReturnCommandHandler<T, R> handler1) {
            consumerRegistry.createPublisher(handler1.getCommandType(), handler);
        }
    }

    @Override
    public <T extends Record> void unregister(NoReturnCommandHandler<T> handler) {
        if (handler instanceof AbstractNoReturnCommandHandler<T> handler1) {
            consumerRegistry.clearNoReturnPublisher(handler1.getCommandType());
        }
    }

    @Override
    public <T extends Record, R> void unregister(ReturnCommandHandler<T, R> handler) {
        if (handler instanceof AbstractReturnCommandHandler<T, R> handler1) {
            consumerRegistry.clearReturnPublisher(handler1.getCommandType());
        }
    }

    @Override
    public <T extends Record> void dispatch(Command<T> command) throws CommandHandlerExecutionError {
        NoReturnHandlerExecutor<T> executor = consumerRegistry.getNoReturnPublisher(command.getType());
        executor.execute(command);
    }

    @Override
    public <T extends Record, R> R dispatchAndReturn(Command<T> command) throws CommandHandlerExecutionError {
        ReturnHandlerExecutor<T, R> executor = consumerRegistry.getReturnPublisher(command.getType());
        return executor.submitAndReturn(command);
    }

}

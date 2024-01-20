package net.nexus_flow.core.cqrs.command;

import net.nexus_flow.core.cqrs.reflection.TypeReference;

interface CommandConsumerRegistry {

    static CommandConsumerRegistry getInstance() {
        return CommandConsumerRegistryFactory.getInstance();
    }

    <T extends Record, H extends NoReturnCommandHandler<T>> void createPublisher(TypeReference<T> typeReference, H handler);

    <T extends Record> NoReturnHandlerExecutor<T> getNoReturnPublisher(TypeReference<T> typeReference);

    <T extends Record> void clearNoReturnPublisher(TypeReference<T> typeReference);

    <T extends Record, R, H extends ReturnCommandHandler<T, R>> void createPublisher(TypeReference<T> typeReference, H handler);

    <T extends Record, R> ReturnHandlerExecutor<T, R> getReturnPublisher(TypeReference<T> typeReference);

    <T extends Record, R> void clearReturnPublisher(TypeReference<T> typeReference);

}

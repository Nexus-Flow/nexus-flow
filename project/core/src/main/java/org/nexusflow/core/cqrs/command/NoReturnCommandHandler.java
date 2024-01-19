package org.nexusflow.core.cqrs.command;

public sealed interface NoReturnCommandHandler<T extends Record> extends CommandHandler<T, Void, NoReturnCommandHandler<T>> permits AbstractNoReturnCommandHandler, NoReturnCommandHandlerInternal {
    // Aquí no se expone el método `handle`.
}

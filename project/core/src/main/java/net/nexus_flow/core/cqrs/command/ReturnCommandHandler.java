package net.nexus_flow.core.cqrs.command;

public sealed interface ReturnCommandHandler<T extends Record, R> extends CommandHandler<T, R, ReturnCommandHandler<T, R>> permits AbstractReturnCommandHandler, ReturnCommandHandlerInternal {
    // Aquí no se expone el método `handleAndReturn`.
}
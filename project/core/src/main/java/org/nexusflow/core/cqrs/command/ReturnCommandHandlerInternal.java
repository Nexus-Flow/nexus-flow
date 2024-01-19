package org.nexusflow.core.cqrs.command;

import java.util.concurrent.Callable;

non-sealed interface ReturnCommandHandlerInternal<T extends Record, R> extends ReturnCommandHandler<T, R> {
    Callable<R> handleAndReturn(T command);
}
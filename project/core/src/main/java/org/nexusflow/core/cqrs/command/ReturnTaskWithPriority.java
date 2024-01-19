package org.nexusflow.core.cqrs.command;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public record ReturnTaskWithPriority<T extends Record, R>(Command<T> command, Callable<R> task, CompletableFuture<R> future) implements TaskWithPriority<T, R> {

    @Override
    public int getPriority() {
        return command.getPriority();
    }

    @Override
    public int compareTo(TaskWithPriority<T, R> other) {
        // Priorities are in descending order, hence the comparison is reversed
        return Integer.compare(other.getPriority(), this.getPriority());
    }

}
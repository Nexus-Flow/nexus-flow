package org.nexusflow.core.cqrs.command;

public interface TaskWithPriority<T extends Record, R> extends Comparable<TaskWithPriority<T, R>> {
    int getPriority();

    Command<T> command();

}

package net.nexus_flow.core.cqrs.command;

interface TaskWithPriority<T extends Record, R> extends Comparable<TaskWithPriority<T, R>> {
    int getPriority();

    Command<T> command();

}

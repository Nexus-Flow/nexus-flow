package net.nexus_flow.core.cqrs.command;

record NoReturnTaskWithPriority<T extends Record>(Command<T> command, Runnable task) implements TaskWithPriority<T, Void> {

    @Override
    public int getPriority() {
        return command.getPriority();
    }

    @Override
    public int compareTo(TaskWithPriority<T, Void> other) {
        // Priorities are in descending order, hence the comparison is reversed
        return Integer.compare(other.getPriority(), this.getPriority());
    }

}
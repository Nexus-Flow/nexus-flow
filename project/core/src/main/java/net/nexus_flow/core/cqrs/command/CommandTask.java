package net.nexus_flow.core.cqrs.command;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.nexus_flow.core.runtime.ExecutionContext;

/**
 * unified task record for the consolidated {@link DefaultCommandHandlerExecutor}.
 *
 * <p>Replaces the legacy {@code NoReturnTaskWithPriority} / {@code ReturnTaskWithPriority} split
 * (and the {@code TaskWithPriority} super-interface). The single record drives both the
 * fire-and-forget and value-producing pipelines:
 *
 * <ul>
 * <li>{@link #task()} is always present and produces the handler's result. For the void path the
 * inner {@link Callable} returns {@code null} after running the underlying {@code Runnable}.
 * <li>{@link #future()} is {@code null} on the void path and non-{@code null} on the
 * value-producing path; the cooperative drain loop in {@link DefaultCommandHandlerExecutor}
 * discriminates paths on that field exactly once.
 * <li>{@link #context()} carries the {@link ExecutionContext} captured at enqueue time so the
 * poll loop can purge doomed heads without consuming a permit.
 * </ul>
 *
 * <p>Priorities are descending: highest-priority task drains first.
 *
 * @param command the original {@link Command} (kept for re-queue paths and diagnostics)
 * @param task    the executable form of the handler invocation
 * @param future  the result sink for the value-producing path; {@code null} on the void path
 * @param context the captured-at-enqueue {@link ExecutionContext}
 * @param <T>     the command body record type
 * @param <R>     the handler return type ({@link Void} on the void path)
 */
record CommandTask<T extends Record, R>(
                                        Command<T> command, Callable<R> task, CompletableFuture<R> future, ExecutionContext context)
        implements Comparable<CommandTask<T, R>> {

    int getPriority() {
        return command.getPriority();
    }

    /** {@code true} iff this task belongs to the fire-and-forget path. */
    boolean isVoidPath() {
        return future == null;
    }

    @Override
    public int compareTo(CommandTask<T, R> other) {
        // Descending priority order: higher priority drains first.
        return Integer.compare(other.getPriority(), this.getPriority());
    }
}

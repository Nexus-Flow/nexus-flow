package net.nexus_flow.core.cqrs.command;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable per-task execution context used by the void-path command executor to thread parent
 * metadata through nested dispatches and propagate uncaught exceptions to the root handler.
 *
 * <p>Each invocation of the void-path executor allocates a fresh {@code ThreadContext} bound into a
 * {@link java.lang.ScopedValue} for the duration of the task. Nested synchronous dispatches see the
 * outer context as their {@link #parent()} via lexical scope; tasks forked onto virtual threads
 * carry the captured parent through the submitting lambda's closure and rebind inside the worker.
 *
 * <p><b>Design notes.</b> The previous {@code InheritableThreadLocal}-backed tree exposed a
 * write-only {@code childContexts} list, an {@code errorFlag} that was never read, and a
 * self-parent footgun: when a synchronous re-dispatch reused the same thread, the cached
 * thread-local was set as its own parent and the children {@link
 * java.util.concurrent.CopyOnWriteArrayList} grew unbounded. Switching to an immutable record-like
 * class plus {@code ScopedValue} eliminates both classes of bug structurally — there is no shared
 * mutable state to corrupt and no carrier thread can leak a stale context onto an unrelated
 * dispatch.
 */
public final class ThreadContext {

    private static final Logger LOG = System.getLogger(ThreadContext.class.getName());

    private final long                    threadId;
    private final TaskType                taskType;
    private final @Nullable ThreadContext parent;

    /**
     * Creates a new context.
     *
     * @param threadId id of the thread that will execute the task
     * @param taskType logical task type; never {@code null}
     * @param parent   parent context for nested dispatches, or {@code null} for a root task
     */
    public ThreadContext(long threadId, TaskType taskType, @Nullable ThreadContext parent) {
        this.threadId = threadId;
        this.taskType = Objects.requireNonNull(taskType, "taskType");
        this.parent   = parent;
    }

    /**
     * Returns the id of the thread bound to this context.
     *
     * @return the tracked thread id
     */
    public long threadId() {
        return threadId;
    }

    /**
     * Returns the logical task type associated with this context.
     *
     * @return task type; never {@code null}
     */
    public TaskType taskType() {
        return taskType;
    }

    /**
     * Returns the parent context, when this task was dispatched from inside another task's scope.
     *
     * @return optional parent context
     */
    public Optional<ThreadContext> parent() {
        return Optional.ofNullable(parent);
    }

    /**
     * Marks this context as failed and bubbles the exception up the parent chain. At the root, the
     * exception is handed to the thread's {@link Thread#getUncaughtExceptionHandler()
     * uncaughtExceptionHandler} and then re-thrown so the carrier strategy can observe it.
     *
     * @param ex uncaught exception to propagate; never {@code null}
     * @throws RuntimeException always rethrows {@code ex} at the root context
     */
    public void notifyUncaughtException(RuntimeException ex) {
        RuntimeException runtimeException = Objects.requireNonNull(ex, "ex");
        if (parent != null) {
            LOG.log(Level.DEBUG, () -> "Propagating exception to parent threadId=" + parent.threadId());
            parent.notifyUncaughtException(runtimeException);
            return;
        }
        Thread.currentThread()
                .getUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), runtimeException);
        throw runtimeException;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "ThreadContext{"
                + "threadId="
                + threadId
                + ", taskType="
                + taskType
                + ", parentThreadId="
                + (parent != null ? Long.toString(parent.threadId) : "<root>")
                + '}';
    }
}

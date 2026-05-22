package net.nexus_flow.core.cqrs.command;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builder of virtual-thread {@link ExecutorService} instances for the runtime to own.
 *
 * <p>The process-wide singleton {@code getExecutor()} accessor and the matching static {@code
 * shutdown()} have been removed. Each {@code FlowRuntime} owns exactly one executor created here
 * via {@link #createExecutor(String)} and shuts it down via {@link #shutdown(ExecutorService,
 * Duration)}. Two runtimes in the same JVM therefore have independent executor pools and cannot
 * poison each other on close.
 */
public final class CommandExecutorServiceFactory {

    /** Default graceful shutdown timeout. */
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private CommandExecutorServiceFactory() {
        // utility
    }

    /**
     * Builds a fresh thread-per-task executor backed by virtual threads.
     *
     * @param threadNamePrefix prefix applied to every virtual thread name created by the executor
     * @return runtime-owned executor service for command work
     */
    public static ExecutorService createExecutor(String threadNamePrefix) {
        ThreadFactory threadFactory = new CommandVirtualThreadFactory(threadNamePrefix);
        return Executors.newThreadPerTaskExecutor(threadFactory);
    }

    /**
     * Gracefully shuts down the supplied executor within the provided timeout budget.
     *
     * @param executor executor to stop
     * @param timeout  graceful shutdown timeout; must not be negative
     * @throws IllegalArgumentException if {@code timeout} is negative
     */
    public static void shutdown(ExecutorService executor, Duration timeout) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be >= 0, got: " + timeout);
        }
        if (executor.isShutdown()) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException _) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class CommandVirtualThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String        namePrefix;

        /**
         * Creates a virtual-thread factory with the supplied naming prefix.
         *
         * @param namePrefix prefix applied to created thread names
         */
        CommandVirtualThreadFactory(String namePrefix) {
            this.namePrefix = Objects.requireNonNull(namePrefix, "namePrefix");
        }

        /**
         * Creates a new named virtual thread.
         *
         * @param runnable task to run on the new thread
         * @return unstarted virtual thread bound to {@code runnable}
         */
        @Override
        public Thread newThread(Runnable runnable) {
            Objects.requireNonNull(runnable, "runnable");
            Thread thread = Thread.ofVirtual().inheritInheritableThreadLocals(true).unstarted(runnable);
            thread.setName(String.format("%s-%d", namePrefix, threadNumber.getAndIncrement()));
            return thread;
        }
    }
}

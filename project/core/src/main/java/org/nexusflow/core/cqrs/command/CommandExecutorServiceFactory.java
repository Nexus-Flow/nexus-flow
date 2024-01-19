package org.nexusflow.core.cqrs.command;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class CommandExecutorServiceFactory {

    private static volatile ExecutorService executor;

    public static ExecutorService getExecutor(String threadNamePrefix) {
        if (executor == null) {
            synchronized (CommandExecutorServiceFactory.class) {
                if (executor == null) {
                    ThreadFactory threadFactory = new CommandVirtualThreadFactory(threadNamePrefix);
                    executor = Executors.newThreadPerTaskExecutor(threadFactory);
                }
            }
        }
        return executor;
    }

    private static class CommandVirtualThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        CommandVirtualThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = Thread.ofVirtual().unstarted(r);
            thread.setName(STR."\{namePrefix}-\{threadNumber.getAndIncrement()}");
            return thread;
        }
    }
}

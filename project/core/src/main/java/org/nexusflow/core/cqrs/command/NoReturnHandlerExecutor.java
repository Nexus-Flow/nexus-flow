package org.nexusflow.core.cqrs.command;

import org.nexusflow.core.cqrs.event.*;
import org.nexusflow.core.ddd.DomainEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

class NoReturnHandlerExecutor<T extends Record> implements CommandHandlerExecutor<T, Void, NoReturnCommandHandler<T>>, AutoCloseable {
    private static final Logger logger = Logger.getLogger(NoReturnHandlerExecutor.class.getName());
    private final NoReturnCommandHandlerInternal<T> handler;
    private final ExecutorService executor;
    private final PriorityBlockingQueue<NoReturnTaskWithPriority<T>> taskQueue;
    private final Semaphore semaphore;
    private final InitializationType initializationType;
    private final DomainEventContext eventContext;
    private final AtomicBoolean sagaEnabled;
    private BackoffStrategy backoffStrategy = new ExponentialBackoffStrategy(1000);
    private volatile int concurrencyLevel;
    private volatile boolean running;

    public NoReturnHandlerExecutor(NoReturnCommandHandler<T> handler) {
        this.handler = resolveCommandHandler(handler);
        this.concurrencyLevel = Math.max(handler.getConcurrencyLevel(), 0);
        this.sagaEnabled = new AtomicBoolean(handler.isSagaEnabled());
        this.eventContext = DomainEventContextHolder.getContext();
        this.executor = CommandExecutorServiceFactory.getExecutor("vthread");
        this.taskQueue = concurrencyLevel > 0 ? new PriorityBlockingQueue<>(concurrencyLevel, Comparator.comparingInt(NoReturnTaskWithPriority<T>::getPriority).reversed()) : null;
        this.semaphore = concurrencyLevel > 0 ? new Semaphore(concurrencyLevel) : null;
        this.running = true;
        this.initializationType = handler.getInitializationType();

        initializeExecution();
    }

    @Override
    public void execute(Command<T> command) {
        Runnable task = () -> handler.handle(command.getBody()).run();
        if (concurrencyLevel == 0) {
            executeWithoutConcurrencyControl(task);
        } else {
            executeWithConcurrencyControl(command, task);
        }
    }

    @Override
    public void execute(List<Command<T>> commands) {
        commands.stream().sorted(Comparator.comparingInt(Command<T>::getPriority).reversed()).forEachOrdered(this::execute);
    }

    @Override
    public Void submitAndReturn(Command<T> command) {
        throw new UnsupportedOperationException("NoReturnCommandHandler does not support operations that return a result.");
    }

    @Override
    public List<Void> submitAndReturn(List<Command<T>> commands) {
        throw new UnsupportedOperationException("NoReturnCommandHandler does not support operations that return a result.");
    }

    @Override
    public void close() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void initializeExecution() {
        if (concurrencyLevel == 0 && initializationType.isEager()) {
            Class<?> handlerClass = resolveClassName();
            logger.warning(String.format("%s Initialized with Eager type but concurrency level is 0. Eager execution will not apply. Please review the CommandHandler configuration.",
                    handlerClass.getSimpleName()));
        }
        if (concurrencyLevel > 0 && initializationType.isEager()) startTaskExecution();
    }

    private Class<?> resolveClassName() {
        Class<?> handlerClass = handler.getClass();
        if (handlerClass.isAnonymousClass()) {
            handlerClass = handlerClass.getSuperclass();
        } else if (handlerClass.isMemberClass()) {
            handlerClass = handlerClass.getEnclosingClass();
        }
        return handlerClass;
    }

    private NoReturnCommandHandlerInternal<T> resolveCommandHandler(NoReturnCommandHandler<T> handler) {
        if (handler instanceof NoReturnCommandHandlerInternal<T> handlerInternal) {
            return handlerInternal;
        } else if (handler instanceof AbstractNoReturnCommandHandler<T> abstractHandler) {
            return abstractHandler.getInnerHandler();
        } else {
            throw new IllegalArgumentException("Handler must be an instance of NoReturnCommandHandlerInternal or AbstractNoReturnCommandHandler");
        }
    }

    private void executeWithConcurrencyControl(Command<T> command, Runnable task) {
        enqueueTask(command, task);
        if (initializationType.isLazy()) tryExecuteNextTask();
    }

    private void executeWithoutConcurrencyControl(Runnable task) {
        if (sagaEnabled.get()) {
            if (eventContext instanceof ScopedDomainEventContext scopedDomainEventContext) {
                CompletableFuture.runAsync(() ->
                                ScopedValue.runWhere(scopedDomainEventContext.getScopedValue(),
                                        new ArrayList<>(),
                                        () -> processTask(task, scopedDomainEventContext)), executor)
                        .join();
            } else if (eventContext instanceof ThreadLocalDomainEventContext threadLocalDomainEventContext) {
                CompletableFuture.runAsync(() -> processTask(task, threadLocalDomainEventContext), executor).join();
            } else {
                CompletableFuture.runAsync(task, executor).join();
            }
        } else {
            if (eventContext instanceof ScopedDomainEventContext scopedDomainEventContext) {
                executor.submit(() -> ScopedValue.runWhere(scopedDomainEventContext.getScopedValue(),
                        new ArrayList<>(),
                        () -> processTask(task, scopedDomainEventContext))
                );
            } else if (eventContext instanceof ThreadLocalDomainEventContext threadLocalDomainEventContext) {
                executor.submit(() -> processTask(task, threadLocalDomainEventContext));
            } else {
                executor.submit(task);
            }
        }
    }

    private void processTask(Runnable task, DomainEventContext currentThreadContext) {
        task.run();
        if (currentThreadContext.hasEventsRecorded()) {
            for (DomainEvent event : currentThreadContext.getEvents()) {
                logger.info("Processing event...");
                // Procesa cada evento en modo sincrónico si es una saga, de lo contrario en modo asíncrono
                EventBus.getInstance().dispatch(event, sagaEnabled.get());
            }
        }
    }

    private void enqueueTask(Command<T> command, Runnable task) {
        while (!taskQueue.offer(new NoReturnTaskWithPriority<>(command, task))) {
            logger.warning("Task queue is full. Applying backoff strategy.");
            try {
                backoffStrategy.backoff();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (backoffStrategy.isInBackoffState()) {
            logger.info("Task enqueued after backoff.");
            backoffStrategy = new ExponentialBackoffStrategy(1000);
        }
    }

    private void tryExecuteNextTask() {
        if (!taskQueue.isEmpty()) {
            executor.submit(() -> {
                while (!taskQueue.isEmpty()) {
                    try {
                        semaphore.acquire();
                        NoReturnTaskWithPriority<T> taskWithPriority = taskQueue.poll();
                        if (taskWithPriority != null) {
                            executeTask(taskWithPriority);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } finally {
                        semaphore.release();
                    }
                }
            });
        }
    }

    private void executeTask(NoReturnTaskWithPriority<T> taskWithPriority) {
        if (sagaEnabled.get()) {
            if (eventContext instanceof ScopedDomainEventContext scopedDomainEventContext) {
                CompletableFuture.runAsync(() -> ScopedValue.runWhere(scopedDomainEventContext.getScopedValue(), new ArrayList<>(), () -> processTask(taskWithPriority.task(), scopedDomainEventContext)), executor).join();
            } else if (eventContext instanceof ThreadLocalDomainEventContext threadLocalDomainEventContext) {
                CompletableFuture.runAsync(() -> processTask(taskWithPriority.task(), threadLocalDomainEventContext), executor).join();
            } else {
                CompletableFuture.runAsync(taskWithPriority.task(), executor).join();
            }
        } else {
            if (eventContext instanceof ScopedDomainEventContext scopedDomainEventContext) {
                executor.submit(() -> ScopedValue.runWhere(scopedDomainEventContext.getScopedValue(), new ArrayList<>(), () -> processTask(taskWithPriority.task(), scopedDomainEventContext)));
            } else if (eventContext instanceof ThreadLocalDomainEventContext threadLocalDomainEventContext) {
                executor.submit(() -> processTask(taskWithPriority.task(), threadLocalDomainEventContext));
            } else {
                executor.submit(taskWithPriority.task());
            }
        }
    }

    private void startTaskExecution() {
        for (int i = 0; i < concurrencyLevel; ++i) {
            executor.submit(this::executeNextTask);
        }
    }

    private void executeNextTask() {
        while (running) {
            try {
                semaphore.acquire();
                NoReturnTaskWithPriority<T> taskWithPriority = taskQueue.take(); // Toma la siguiente tarea de la cola
                executeTask(taskWithPriority);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                semaphore.release();
            }
        }
    }

    //    @Override
    public void adjustConcurrency(int newConcurrencyLevel) {
        if (newConcurrencyLevel > 0 && newConcurrencyLevel != this.concurrencyLevel) {
            this.concurrencyLevel = newConcurrencyLevel;
            semaphore.drainPermits();
            semaphore.release(newConcurrencyLevel);
            logger.info(STR."Concurrencia ajustada a: \{newConcurrencyLevel}");
        }
    }

    public boolean isRunning() {
        return running;
    }
}

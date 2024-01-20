package org.nexusflow.core.cqrs.command;

import org.nexusflow.core.cqrs.event.DomainEventContext;
import org.nexusflow.core.cqrs.event.DomainEventContextHolder;
import org.nexusflow.core.cqrs.event.EventBus;
import org.nexusflow.core.cqrs.event.ScopedDomainEventContext;
import org.nexusflow.core.ddd.DomainEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

class ReturnHandlerExecutor<T extends Record, R> implements CommandHandlerExecutor<T, R, ReturnCommandHandler<T, R>>, AutoCloseable {

    private static final Logger logger = Logger.getLogger(ReturnHandlerExecutor.class.getName());
    private final ReturnCommandHandlerInternal<T, R> handler;
    private final ExecutorService executor;
    private final PriorityBlockingQueue<ReturnTaskWithPriority<T, R>> taskQueue;
    private final BackoffStrategy backoffStrategy = new ExponentialBackoffStrategy(1000);
    private final Semaphore semaphore;
    private final InitializationType initializationType;
    private final DomainEventContext eventContext;
    private final AtomicBoolean sagaEnabled;
    private volatile int concurrencyLevel;
    private volatile boolean running;

    public ReturnHandlerExecutor(ReturnCommandHandler<T, R> handler) {
        this.handler = resolveCommandHandler(handler);
        this.concurrencyLevel = Math.max(handler.getConcurrencyLevel(), 0);
        this.sagaEnabled = new AtomicBoolean(handler.isSagaEnabled());
        this.eventContext = DomainEventContextHolder.getContext();
        this.executor = CommandExecutorServiceFactory.getExecutor("vthread");
        this.taskQueue = concurrencyLevel > 0 ? new PriorityBlockingQueue<>(concurrencyLevel, Comparator.comparingInt(ReturnTaskWithPriority<T, R>::getPriority).reversed()) : null;
        this.semaphore = concurrencyLevel > 0 ? new Semaphore(concurrencyLevel) : null;
        this.running = true;
        this.initializationType = handler.getInitializationType();

        initializeExecution();
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

    private ReturnCommandHandlerInternal<T, R> resolveCommandHandler(ReturnCommandHandler<T, R> handler) {
        if (handler instanceof ReturnCommandHandlerInternal<T, R> handlerInternal) {
            return handlerInternal;
        } else if (handler instanceof AbstractReturnCommandHandler<T, R> abstractHandler) {
            return abstractHandler.getInnerHandler();
        } else {
            throw new IllegalArgumentException("Handler must be an instance of ReturnCommandHandlerInternal or AbstractReturnCommandHandler");
        }
    }

    @Override
    public void execute(Command<T> command) {
        throw new UnsupportedOperationException("ReturnCommandHandler does not support operations that return a result.");
    }

    @Override
    public void execute(List<Command<T>> commands) {
        throw new UnsupportedOperationException("ReturnCommandHandler does not support operations that return a result.");
    }

    @Override
    public R submitAndReturn(Command<T> command) {
        logger.info("submitAndReturn llamado con comando: " + command);
        Callable<R> task = () -> handler.handleAndReturn(command.getBody()).call();

        if (concurrencyLevel == 0) {
            return executeWithoutConcurrencyControl(task);
        } else {
            return executeWithConcurrencyControl(command, task);
        }
    }

    @Override
    public List<R> submitAndReturn(List<Command<T>> commands) {
        return commands.stream()
                .sorted(Comparator.comparingInt(Command<T>::getPriority).reversed())
                .map(this::submitAndReturn)
                .toList();
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

    private R executeWithoutConcurrencyControl(Callable<R> task) {
        try {
            if (eventContext instanceof ScopedDomainEventContext scopedDomainEventContext) {
                return ScopedValue.callWhere(scopedDomainEventContext.getScopedValue(), new ArrayList<>(), () -> processTask(task));
            } else {
                return processTask(task);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error executing task", e);
            throw new RuntimeException(e);
        }
    }

    private R executeWithConcurrencyControl(Command<T> command, Callable<R> task) {
        Future<R> future = enqueueTask(command, task);
        if (initializationType.isLazy()) {
            tryExecuteNextTask();
        }
        return getFutureResult(future);
    }

    private R processTask(Callable<R> task) throws Exception {
        R result = task.call();
        handleDomainEvents();
        return result;
    }

    private R getFutureResult(Future<R> future) {
        try {
            return future != null ? future.get() : null;
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.SEVERE, "Error obtaining task result", e);
            return null;
        }
    }

    private void handleDomainEvents() {
        if (eventContext.hasEventsRecorded()) {
            List<DomainEvent> events = eventContext.getEvents();
            EventBus eventBus = EventBus.getInstance();
            for (DomainEvent event : events) {
                eventBus.dispatch(event, sagaEnabled.get());
            }
            eventContext.clearEvents();
        }
    }

    private Future<R> enqueueTask(Command<T> command, Callable<R> task) {
        CompletableFuture<R> future = new CompletableFuture<>();

        ReturnTaskWithPriority<T, R> taskWithPriority = new ReturnTaskWithPriority<>(command, task, future);
        if (!taskQueue.offer(taskWithPriority)) {
            handleTaskQueueFull(taskWithPriority);
        }

        if (initializationType.isLazy()) {
            executeNextTask();
        }

        return future;
    }

    private void handleTaskQueueFull(ReturnTaskWithPriority<T, R> taskWithPriority) {
        logger.warning("Task queue is full. Applying backoff strategy.");
        try {
            backoffStrategy.backoff();
            enqueueTask(taskWithPriority.command(), taskWithPriority.task());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskWithPriority.future().completeExceptionally(e);
        }
    }

    private void tryExecuteNextTask() {
        executor.submit(() -> {
            while (running && !taskQueue.isEmpty()) {
                try {
                    semaphore.acquire();
                    ReturnTaskWithPriority<T, R> taskWithPriority = taskQueue.poll();
                    if (taskWithPriority != null) {
                        executeTask(taskWithPriority);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    semaphore.release();
                }
            }
        });
    }

    private void executeTask(ReturnTaskWithPriority<T, R> taskWithPriority) {
        Callable<R> task = taskWithPriority.task();
        CompletableFuture<R> future = taskWithPriority.future();

        try {
            if (eventContext instanceof ScopedDomainEventContext scopedDomainEventContext) {
                ScopedValue.runWhere(scopedDomainEventContext.getScopedValue(), new ArrayList<>(), () -> completeFuture(task, future));
            } else {
                completeFuture(task, future);
            }
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    private void completeFuture(Callable<R> task, CompletableFuture<R> future) {
        try {
            R result = task.call();
            handleDomainEvents();
            future.complete(result);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    private void startTaskExecution() {
        for (int i = 0; i < concurrencyLevel; ++i) {
            executor.submit(this::executeNextTask);
        }
    }

    private void executeNextTask() {
        if (!taskQueue.isEmpty() && semaphore.tryAcquire()) {
            executor.submit(() -> {
                try {
                    ReturnTaskWithPriority<T, R> taskWithPriority = taskQueue.poll();
                    if (taskWithPriority != null) {
                        executeTask(taskWithPriority);
                    }
                } finally {
                    semaphore.release();
                }
            });
        }
    }

    public void adjustConcurrency(int newConcurrencyLevel) {
        if (newConcurrencyLevel > 0 && newConcurrencyLevel != this.concurrencyLevel) {
            this.concurrencyLevel = newConcurrencyLevel;
            semaphore.drainPermits();
            semaphore.release(newConcurrencyLevel);
            logger.info("Concurrencia ajustada a: " + newConcurrencyLevel);
        }
    }

}
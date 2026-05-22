package net.nexus_flow.core.cqrs.command;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError;
import net.nexus_flow.core.cqrs.event.DomainEventContext;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.cqrs.event.ScopedDomainEventContext;
import net.nexus_flow.core.cqrs.event.ThreadLocalDomainEventContext;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.*;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import org.jspecify.annotations.Nullable;

/**
 * Single command-handler executor pipeline.
 *
 * <p>This class consolidates two near-clone executors that previously extended a shared abstract
 * base. Before consolidation the runtime carried {@code NoReturnHandlerExecutor} (fire-and-forget,
 * {@link Runnable} tasks) and {@code ReturnHandlerExecutor} (value-producing, {@link Callable} +
 * {@link CompletableFuture}) — both hoisting ~80 % of the lifecycle, queue, semaphore,
 * back-pressure, cooperative drain and event-drain logic. This consolidation collapses the
 * remaining differential into a {@code voidPath} flag and a single {@link CommandTask} queue
 * element.
 *
 * <p>The two construction paths surface through the {@link #forNoReturn(NoReturnCommandHandler,
 * EventBus, ExecutorService, FlowRuntime) forNoReturn(...)} and {@link
 * #forReturn(ReturnCommandHandler, EventBus, ExecutorService, FlowRuntime) forReturn(...)}
 * factories. They are the only public entry points; the constructor is package-private to keep
 * wiring through the factories (which also run {@link #initializeExecution()} so eager drainer
 * carriers start before the first dispatch).
 *
 * <p>Observable behavior is unchanged:
 *
 * <ul>
 * <li>Fire-and-forget keeps the {@link ThreadContext}-based parent/child carrier tree.
 * <li>Value-producing keeps the synchronous {@code submitAndReturn} block-on-future contract and
 * the {@link CommandHandlerExecutionError} wrap for arbitrary handler exceptions.
 * </ul>
 *
 * @param <T> the command body record type
 * @param <R> the handler return type ({@link Void} on the void path)
 * @param <H> the outer (configuration) handler type
 */
final class DefaultCommandHandlerExecutor<T extends Record, R, H extends CommandHandler<T, R, H>>
        implements CommandHandlerExecutor<T, R, H>, AutoCloseable {

    private static final Logger LOG = System.getLogger(DefaultCommandHandlerExecutor.class.getName());

    /**
     * Per-task parent context — void path only. {@link ScopedValue} is used in place of {@link
     * InheritableThreadLocal} so that nested synchronous dispatches see the outer task's context via
     * lexical scope and forked virtual threads carry the parent through the submitting lambda's
     * closure rather than relying on carrier-thread inheritance. Eliminates the self-parent footgun
     * the previous {@code InheritableThreadLocal} setup had on recursive Inline dispatch.
     */
    static final ScopedValue<ThreadContext> CURRENT_TC = ScopedValue.newInstance();

    // Configuration captured at construction
    /**
     * The handler reference exposed to user code. Carries {@link CommandHandler#getCommandSettings()}
     * / {@link CommandHandler#isSagaEnabled()}.
     *
     * <p>Field name pinned by {@code CooperativeCancellationInPollLoopTest} (reflective lookup); do
     * not rename without updating that test.
     */
    final H outerHandler;

    final ExecutorService    executor;
    final EventBus           eventBus;
    final FlowRuntime        runtime;
    final InitializationType initializationType;

    /**
     * Domain event sink resolved once at construction. Always identical to {@link
     * DomainEventContext#current()} at that point.
     */
    final DomainEventContext eventContext;

    final AtomicBoolean sagaEnabled;

    /** {@code null} when {@code concurrencyLevel == 0}. */
    final PriorityBlockingQueue<CommandTask<T, R>> taskQueue;

    /**
     * Per-handler concurrency gate.
     *
     * <p>Field name pinned by {@code CooperativeCancellationInPollLoopTest}; do not rename without
     * updating that test.
     */
    final Semaphore semaphore;

    /** per-handler back-pressure gate (no-op when defaults). */
    final HandlerBackpressureGate backpressureGate;

    /**
     * {@code true} when this executor was built for a {@link NoReturnCommandHandler}. Drives the few
     * remaining path-dependent branches: thread-context wrapping, future bookkeeping at {@code
     * close()} and {@code failDoomed(...)}, and the legacy logger-name shim.
     */
    private final boolean voidPath;

    /**
     * Produces an executable {@link Callable} per dispatch. For the void path the inner {@link
     * Callable} runs the underlying {@link Runnable} and returns {@code null}; for the return path it
     * forwards to {@code ReturnCommandHandlerInternal#handleAndReturn}.
     */
    private final Function<T, Callable<R>> taskFactory;

    // Mutable runtime state
    volatile int     concurrencyLevel;
    volatile boolean running;

    /**
     * Shared back-off strategy used by the queue-full retry paths ({@link #enqueueVoidTask} and
     * {@link #handleReturnQueueFull}) and read by the drainer ({@link
     * #cooperativeAcquireAndRunHead}'s {@link BackoffStrategy#cancellationPoll()}). The instance is
     * internally thread-safe: {@link ExponentialBackoffStrategy} uses {@code synchronized} methods so
     * concurrent {@link BackoffStrategy#nextWaitAndAdvance()} callers all observe consistent
     * progression. The field itself is {@code final} — the strategy IS the mutable state holder; no
     * {@link AtomicReference} wrapper is required for two primitives + a reset operation.
     *
     * <p>Configuration is sourced from {@code handler.getCommandSettings().backoff()} at construction
     * time. Handler authors override the defaults via {@link
     * CommandSettings.Builder#backoff(BackoffSettings)}.
     */
    private final BackoffStrategy backoffStrategy;

    // Per-dispatch strategy cache
    /**
     * Atomic snapshot of the last resolved strategy and the keys it was resolved from. Using a single
     * {@link AtomicReference} ensures callers either see a fully consistent cache triple or {@code
     * null} — never a partially-updated state.
     */
    private record StrategyCache(
                                 ExecutionStrategy strategy, CommandSettings settingsKey, Object runtimeKey) {
    }

    private final AtomicReference<@Nullable StrategyCache> strategyCache = new AtomicReference<>();

    // Factories

    /**
     * Build a fire-and-forget executor. The factory is preferred over direct construction because it
     * also runs {@link #initializeExecution()} (eager drainer carriers cannot be started from the
     * base constructor before all fields are wired).
     */
    static <T extends Record> DefaultCommandHandlerExecutor<T, Void, NoReturnCommandHandler<T>> forNoReturn(
            NoReturnCommandHandler<T> handler,
            EventBus eventBus,
            ExecutorService executor,
            FlowRuntime runtime) {
        NoReturnCommandHandlerInternal<T> internal = handler.getInternal();
        // Deferred-call factory: every step (including
        // {@code internal.handle(body)} which may itself be eager in some
        // {@link AbstractNoReturnCommandHandler} subclasses) must happen
        // on the carrier thread that actually runs the task. Calling
        // {@code handle(body)} from the dispatching thread would let user
        // code run BEFORE the cooperative drain loop's doomed-head check,
        // breaking the row-10/11 contract pinned by
        // {@code CooperativeCancellationInPollLoopTest}.
        Function<T, Callable<Void>>                                       factory =
                body -> () -> {
                                                                                              internal.handle(body).run();
                                                                                              return null;
                                                                                          };
        DefaultCommandHandlerExecutor<T, Void, NoReturnCommandHandler<T>> e       =
                new DefaultCommandHandlerExecutor<>(handler, true, factory, eventBus, executor, runtime);
        e.initializeExecution();
        return e;
    }

    /**
     * Build a value-producing executor. See {@link #forNoReturn(NoReturnCommandHandler, EventBus,
     * ExecutorService, FlowRuntime) forNoReturn(...)} for the rationale of running initialization
     * outside the constructor.
     *
     * <p>The deferred-call factory ensures that {@code internal.handleAndReturn(body)} runs on the
     * carrier thread, so the cooperative drain loop's doomed-head purge can short-circuit a canceled
     * / deadline-expired dispatch before user code observes the body (pinned by {@code
     * CooperativeCancellationInPollLoopTest}).
     */
    static <T extends Record, R> DefaultCommandHandlerExecutor<T, R, ReturnCommandHandler<T, R>> forReturn(
            ReturnCommandHandler<T, R> handler,
            EventBus eventBus,
            ExecutorService executor,
            FlowRuntime runtime) {
        ReturnCommandHandlerInternal<T, R>                              internal = handler.getInternal();
        Function<T, Callable<R>>                                        factory  = body -> () -> internal.handleAndReturn(body).call();
        DefaultCommandHandlerExecutor<T, R, ReturnCommandHandler<T, R>> e        =
                new DefaultCommandHandlerExecutor<>(handler, false, factory, eventBus, executor, runtime);
        e.initializeExecution();
        return e;
    }

    DefaultCommandHandlerExecutor(
            H handler,
            boolean voidPath,
            Function<T, Callable<R>> taskFactory,
            EventBus eventBus,
            ExecutorService executor,
            FlowRuntime runtime) {
        this.outerHandler = Objects.requireNonNull(handler, "handler");
        this.voidPath     = voidPath;
        this.taskFactory  = Objects.requireNonNull(taskFactory, "taskFactory");
        this.eventBus     = Objects.requireNonNull(eventBus, "eventBus");
        this.executor     = Objects.requireNonNull(executor, "executor");
        this.runtime      = Objects.requireNonNull(runtime, "runtime");

        int configuredConcurrency = handler.getConcurrencyLevel();
        if (configuredConcurrency < 0) {
            throw new IllegalArgumentException(
                    "concurrencyLevel must be >= 0, got: " + configuredConcurrency);
        }
        // Read settings before the clamp so the handler-configured cap governs all three sinks
        // (queue initial capacity, semaphore permits, eager-drainer submission loop). See
        // ConcurrencySettings for the cap contract.
        CommandSettings commandSettings     = handler.getCommandSettings();
        int             maxConcurrencyLevel =
                commandSettings != null ? commandSettings.concurrency().maxLevel() : ConcurrencySettings.defaults().maxLevel();
        if (configuredConcurrency > maxConcurrencyLevel) {
            final int    requested   = configuredConcurrency;
            final String handlerName = handler.getClass().getName();
            LOG.log(
                    Level.WARNING,
                    () -> "concurrencyLevel "
                            + requested
                            + " from handler "
                            + handlerName
                            + " exceeds the configured maximum "
                            + maxConcurrencyLevel
                            + "; clamping to "
                            + maxConcurrencyLevel
                            + ". Raise ConcurrencySettings.maxLevel() in CommandSettings to allow a higher"
                            + " cap, or compose a custom bounded executor with external rate limiting for"
                            + " truly unbounded workloads.");
            configuredConcurrency = maxConcurrencyLevel;
        }
        this.concurrencyLevel   = configuredConcurrency;
        this.sagaEnabled        = new AtomicBoolean(handler.isSagaEnabled());
        this.eventContext       = DomainEventContext.current();
        this.initializationType = handler.getInitializationType();
        this.running            = true;

        Comparator<CommandTask<T, R>> comparator =
                (left, right) -> Integer.compare(right.getPriority(), left.getPriority());
        this.taskQueue =
                concurrencyLevel > 0 ? new PriorityBlockingQueue<>(Math.max(concurrencyLevel, 1), comparator) : null;
        this.semaphore = concurrencyLevel > 0 ? new Semaphore(concurrencyLevel) : null;

        HandlerBackpressureSettings bpSettings =
                commandSettings != null ? commandSettings.backpressure() : HandlerBackpressureSettings.DEFAULTS;
        this.backpressureGate = new HandlerBackpressureGate(bpSettings, null, this.concurrencyLevel);

        BackoffSettings backoffSettings =
                commandSettings != null ? commandSettings.backoff() : BackoffSettings.defaults();
        this.backoffStrategy = new ExponentialBackoffStrategy(backoffSettings);
    }

    // Eager / lazy initialization

    private void initializeExecution() {
        if (concurrencyLevel == 0 && initializationType.isEager()) {
            Class<?> handlerClass = resolveOwnerClass();
            LOG.log(
                    Level.WARNING,
                    () -> handlerClass.getSimpleName()
                            + " initialized with Eager type but concurrency level is 0. Eager execution will"
                            + " not apply. Please review the CommandHandler configuration.");
        }
        if (concurrencyLevel > 0 && initializationType.isEager()) {
            for (int i = 0; i < concurrencyLevel; ++i) {
                executor.submit(this::eagerDrainLoop);
            }
        }
    }

    private void eagerDrainLoop() {
        while (running) {
            if (cooperativeAcquireAndRunHead()) {
                return;
            }
        }
    }

    private void tryStartLazyDrainer() {
        if (taskQueue != null && !taskQueue.isEmpty()) {
            executor.submit(this::lazyDrainLoop);
        }
    }

    private void lazyDrainLoop() {
        while (running && taskQueue != null && !taskQueue.isEmpty()) {
            if (cooperativeAcquireAndRunHead()) {
                return;
            }
        }
    }

    // Cooperative drain loop

    // Callers check the "did nothing" case via !cooperativeAcquireAndRunHead(); inversion is
    // intentional.
    //noinspection BooleanMethodIsAlwaysInverted
    private boolean cooperativeAcquireAndRunHead() {
        long pollMs = Math.max(backoffStrategy.cancellationPoll().toMillis(), 1L);

        if (purgeCancelledRun()) {
            return false;
        }
        if (taskQueue.isEmpty()) {
            CommandTask<T, R> head;
            try {
                head = taskQueue.poll(pollMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return true;
            }
            if (head == null) {
                return false;
            }
            return !runHeadUnderPermit(head, pollMs);
        }

        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(pollMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return true;
        }
        if (!acquired) {
            return false;
        }

        CommandTask<T, R> head = taskQueue.poll();
        if (head == null) {
            semaphore.release();
            return false;
        }
        if (isDoomed(head.context())) {
            semaphore.release();
            failDoomed(head);
            backpressureGate.afterCompletion();
            return false;
        }
        try {
            executeTask(head);
        } finally {
            semaphore.release();
            backpressureGate.afterCompletion();
        }
        return false;
    }

    private boolean runHeadUnderPermit(CommandTask<T, R> head, long pollMs) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(pollMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!acquired) {
            taskQueue.offer(head);
            return true;
        }
        if (isDoomed(head.context())) {
            semaphore.release();
            failDoomed(head);
            backpressureGate.afterCompletion();
            return true;
        }
        try {
            executeTask(head);
        } finally {
            semaphore.release();
            backpressureGate.afterCompletion();
        }
        return true;
    }

    private boolean purgeCancelledRun() {
        boolean purgedAny = false;
        while (true) {
            CommandTask<T, R> head = taskQueue.peek();
            if (head == null || !isDoomed(head.context())) {
                return purgedAny;
            }
            CommandTask<T, R> popped = taskQueue.poll();
            if (popped == null) {
                return purgedAny;
            }
            if (popped != head && !isDoomed(popped.context())) {
                taskQueue.offer(popped);
                return purgedAny;
            }
            failDoomed(popped);
            backpressureGate.afterCompletion();
            purgedAny = true;
        }
    }

    private static boolean isDoomed(ExecutionContext ctx) {
        try {
            ctx.throwIfCancelledOrExpired();
            return false;
        } catch (FlowCancellationException | FlowDeadlineExceededException _) {
            return true;
        }
    }

    // Public dispatch surface

    /** {@inheritDoc} */
    @Override
    public void execute(Command<T> command) {
        Command<T> typedCommand = Objects.requireNonNull(command, "command");
        if (!voidPath) {
            throw new UnsupportedOperationException(
                    getClass().getSimpleName()
                            + " does not support fire-and-forget execution. "
                            + "Use a NoReturnCommandHandler or call dispatchAndReturn(...).");
        }
        ThreadContext parentContext = CURRENT_TC.isBound() ? CURRENT_TC.get() : null;
        Callable<R>   base          = taskFactory.apply(typedCommand.getBody());
        Runnable      taskRunnable  =
                wrapWithThreadContext(
                                      () -> {
                                          try {
                                              base.call();
                                          } catch (RuntimeException re) {
                                              throw re;
                                          } catch (Exception e) {
                                              throw new CommandHandlerExecutionError(e);
                                          }
                                      },
                                      parentContext);

        ExecutionContext ctx = currentContextOrRoot();
        if (concurrencyLevel == 0) {
            currentStrategy().run(bindEventSinkRunnable(taskRunnable), ctx);
            return;
        }
        HandlerBackpressureGate.Outcome outcome =
                backpressureGate.beforeEnqueue(ctx, HandlerBackpressureGate.evictHeadOf(taskQueue));
        if (outcome == HandlerBackpressureGate.Outcome.REJECT) {
            throw backpressureGate.buildRejection(ctx);
        }
        // Wrap the ThreadContext-bearing Runnable as a Callable<R> so
        // the queue is uniformly typed; the inner Runnable's exception
        // handling already swallows uncaught failures into the
        // ThreadContext notifier.
        Callable<R> queueTask =
                () -> {
                    taskRunnable.run();
                    return null;
                };
        enqueueVoidTask(typedCommand, queueTask, ctx);
        if (initializationType.isLazy()) {
            tryStartLazyDrainer();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void execute(List<Command<T>> commands) {
        Objects.requireNonNull(commands, "commands");
        if (!voidPath) {
            throw new UnsupportedOperationException(
                    getClass().getSimpleName() + " does not support fire-and-forget execution.");
        }
        commands.stream()
                .sorted(Comparator.comparingInt(Command<T>::getPriority).reversed())
                .forEachOrdered(this::execute);
    }

    /** {@inheritDoc} */
    @Override
    public R submitAndReturn(Command<T> command) {
        Command<T> typedCommand = Objects.requireNonNull(command, "command");
        if (voidPath) {
            throw new UnsupportedOperationException(
                    getClass().getSimpleName()
                            + " does not produce a value. "
                            + "Use a ReturnCommandHandler or call dispatch(...).");
        }
        if (LOG.isLoggable(Level.DEBUG)) {
            LOG.log(Level.DEBUG, () -> "submitAndReturn(): " + typedCommand);
        }
        Callable<R>      task = taskFactory.apply(typedCommand.getBody());
        ExecutionContext ctx  = currentContextOrRoot();

        if (concurrencyLevel == 0) {
            return executeReturnWithoutConcurrencyControl(task, ctx);
        }
        HandlerBackpressureGate.Outcome outcome =
                backpressureGate.beforeEnqueue(ctx, HandlerBackpressureGate.evictHeadOf(taskQueue));
        if (outcome == HandlerBackpressureGate.Outcome.REJECT) {
            throw backpressureGate.buildRejection(ctx);
        }
        Future<R> future = enqueueReturnTask(typedCommand, task, ctx);
        if (initializationType.isLazy()) {
            tryStartLazyDrainer();
        }
        return awaitFutureResult(future);
    }

    /** {@inheritDoc} */
    @Override
    public List<R> submitAndReturn(List<Command<T>> commands) {
        Objects.requireNonNull(commands, "commands");
        if (voidPath) {
            throw new UnsupportedOperationException(
                    getClass().getSimpleName() + " does not produce a value.");
        }
        return commands.stream()
                .sorted(Comparator.comparingInt(Command<T>::getPriority).reversed())
                .map(this::submitAndReturn)
                .toList();
    }

    // Path-specific enqueue helpers

    private void enqueueVoidTask(Command<T> command, Callable<R> queueTask, ExecutionContext ctx) {
        CommandTask<T, R> taskWithPriority = new CommandTask<>(command, queueTask, null, ctx);
        while (!taskQueue.offer(taskWithPriority)) {
            LOG.log(Level.WARNING, "Task queue is full. Applying backoff strategy.");
            // Atomically read the current wait AND advance the shared strategy. Synchronized internally,
            // so concurrent enqueuers each contribute exactly one advance step.
            Duration wait = backoffStrategy.nextWaitAndAdvance();
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Reset on success: synchronized reset atomically restores both internal fields to base.
        // Concurrent advancers racing against this reset see either the pre-reset or post-reset state,
        // never a mixed one; if a concurrent advancer wrote first, the reset just resets again — both
        // outcomes converge on "base state".
        if (backoffStrategy.isInBackoffState()) {
            LOG.log(Level.DEBUG, "Task enqueued after backoff.");
            backoffStrategy.reset();
        }
    }

    private R executeReturnWithoutConcurrencyControl(Callable<R> task, ExecutionContext ctx) {
        try {
            return currentStrategy().run(bindEventSinkCallable(task), ctx);
        } catch (CommandHandlerExecutionError e) {
            throw e;
        } catch (Exception e) {
            throw new CommandHandlerExecutionError(e);
        }
    }

    private Future<R> enqueueReturnTask(Command<T> command, Callable<R> task, ExecutionContext ctx) {
        CompletableFuture<R> future           = new CompletableFuture<>();
        CommandTask<T, R>    taskWithPriority = new CommandTask<>(command, task, future, ctx);
        if (!taskQueue.offer(taskWithPriority)) {
            handleReturnQueueFull(taskWithPriority);
        }
        return future;
    }

    private void handleReturnQueueFull(CommandTask<T, R> taskWithPriority) {
        while (!taskQueue.offer(taskWithPriority)) {
            LOG.log(Level.WARNING, "Task queue is full. Applying backoff strategy.");
            // Same advance pattern as enqueueVoidTask — see there for rationale.
            Duration wait = backoffStrategy.nextWaitAndAdvance();
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                taskWithPriority.future().completeExceptionally(e);
                return;
            }
        }
        if (backoffStrategy.isInBackoffState()) {
            LOG.log(Level.DEBUG, "Task enqueued after backoff.");
            backoffStrategy.reset();
        }
    }

    private R awaitFutureResult(Future<R> future) {
        Objects.requireNonNull(future, "future");
        try {
            return future.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CommandHandlerExecutionError(ie);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FlowCancellationException fc) {
                throw ThrowableUtils.withSuppressed(fc, e);
            }
            if (cause instanceof FlowDeadlineExceededException fd) {
                throw ThrowableUtils.withSuppressed(fd, e);
            }
            if (cause == null) {
                throw new CommandHandlerExecutionError(e);
            }
            throw ThrowableUtils.withSuppressed(new CommandHandlerExecutionError(cause), e);
        }
    }

    // Queue head execution & failure paths

    private void executeTask(CommandTask<T, R> head) {
        // Check command deadline before executing the task.
        java.time.Instant deadline = head.command().getDeadline();
        if (deadline != null && java.time.Instant.now().isAfter(deadline)) {
            if (head.isVoidPath()) {
                LOG.log(
                        Level.WARNING,
                        () -> "Command deadline exceeded; dropping task " + head.command().getCommandId());
                return;
            }
            head.future().completeExceptionally(new FlowDeadlineExceededException(deadline));
            return;
        }
        if (head.isVoidPath()) {
            // Void path: head.task() is a Callable<R> wrapping the
            // ThreadContext-bearing Runnable. Adapt back to Runnable for
            // the strategy; the wrapper already handles exceptions.
            currentStrategy()
                    .run(
                         bindEventSinkRunnable(
                                               () -> {
                                                   try {
                                                       head.task().call();
                                                   } catch (RuntimeException re) {
                                                       throw re;
                                                   } catch (Exception e) {
                                                       LOG.log(
                                                               Level.ERROR, () -> "Uncaught exception during void task execution", e);
                                                   }
                                               }),
                         head.context());
            return;
        }
        CompletableFuture<R> future = head.future();
        try {
            currentStrategy()
                    .run(bindEventSinkRunnable(() -> completeFuture(head.task(), future)), head.context());
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    private void failDoomed(CommandTask<T, R> task) {
        if (task.isVoidPath()) {
            if (LOG.isLoggable(Level.DEBUG)) {
                LOG.log(Level.DEBUG, () -> "Dropping doomed task (cancelled or expired ctx)");
            }
            return;
        }
        CompletableFuture<R> future = task.future();
        try {
            task.context().throwIfCancelledOrExpired();
            // If the ctx flips between isDoomed() and here, surface a
            // cancellation so callers never see a hung future.
            future.completeExceptionally(new FlowCancellationException());
        } catch (FlowCancellationException | FlowDeadlineExceededException ex) {
            future.completeExceptionally(ex);
        }
    }

    private void onCloseDropTask(CommandTask<T, R> task) {
        if (task.isVoidPath()) {
            // Void path: nothing to release; the close() warning already
            // accounted for the drop.
            return;
        }
        task.future()
                .completeExceptionally(
                                       new CancellationException("Handler executor closed with pending tasks"));
    }

    // Strategy resolution

    private ExecutionStrategy currentStrategy() {
        CommandSettings settingsKey = outerHandler.getCommandSettings();
        StrategyCache   cached      = strategyCache.get();
        if (cached != null && cached.settingsKey() == settingsKey && cached.runtimeKey() == runtime) {
            return cached.strategy();
        }
        ExecutionStrategy resolved = ExecutionStrategyResolver.resolveStrategy(outerHandler, runtime);
        strategyCache.set(new StrategyCache(resolved, settingsKey, runtime));
        return resolved;
    }

    private static ExecutionContext currentContextOrRoot() {
        return FlowScope.current().orElseGet(ExecutionContext::root);
    }

    // Event sink binding

    /**
     * Wrap {@code task} with the per-handler domain-event sink binding and route through {@link
     * #processTaskRunnable(Runnable)} so emitted events are drained through {@link
     * HandlerEventDrain}.
     */
    private Runnable bindEventSinkRunnable(Runnable task) {
        return switch (eventContext) {
            case ScopedDomainEventContext scoped ->
                 () -> ScopedValue.where(scoped.getScopedValue(), new ScopedDomainEventContext.Sink())
                         .run(() -> processTaskRunnable(task));
            case ThreadLocalDomainEventContext _ -> () -> processTaskRunnable(task);
        };
    }

    /**
     * Value-path counterpart of {@link #bindEventSinkRunnable(Runnable)} used by the inline
     * (no-concurrency) {@code submitAndReturn} path.
     */
    private Callable<R> bindEventSinkCallable(Callable<R> task) {
        return switch (eventContext) {
            case ScopedDomainEventContext scoped ->
                 () -> ScopedValue.where(scoped.getScopedValue(), new ScopedDomainEventContext.Sink())
                         .call(() -> processTaskCallable(task));
            case ThreadLocalDomainEventContext _ -> () -> processTaskCallable(task);
        };
    }

    private void processTaskRunnable(Runnable task) {
        task.run();
        drainRecordedEvents();
    }

    private R processTaskCallable(Callable<R> task) throws Exception {
        R result = task.call();
        drainRecordedEvents();
        return result;
    }

    private void completeFuture(Callable<R> task, CompletableFuture<R> future) {
        try {
            R result = task.call();
            drainRecordedEvents();
            future.complete(result);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    private void drainRecordedEvents() {
        if (!eventContext.hasEventsRecorded()) {
            return;
        }
        List<DomainEvent> events = eventContext.getEvents();
        try {
            HandlerEventDrain.drain(events, runtime, eventBus, sagaEnabled.get());
        } finally {
            eventContext.clearEvents();
        }
    }

    // Lifecycle

    /** {@inheritDoc} */
    @Override
    public void close() {
        running = false;
        if (taskQueue == null || taskQueue.isEmpty()) {
            return;
        }
        Logger closeLog = System.getLogger(DefaultCommandHandlerExecutor.class.getName());
        int    lost     = taskQueue.size();
        String pathName = voidPath ? "NoReturnHandlerExecutor" : "ReturnHandlerExecutor";
        closeLog.log(
                     Level.WARNING,
                     () -> "close-during-saturation: "
                             + lost
                             + " queued task(s) lost during "
                             + pathName
                             + " shutdown");
        CommandTask<T, R> task;
        while ((task = taskQueue.poll()) != null) {
            try {
                onCloseDropTask(task);
            } catch (RuntimeException ex) {
                closeLog.log(Level.WARNING, () -> "Exception while releasing pending task on close", ex);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void adjustConcurrency(int newConcurrencyLevel) {
        // Boundary cases are silent no-ops to preserve the historic
        // "best-effort runtime tuning" contract — metrics observers
        // (Micrometer/OTEL saturation feedback) may call this from observers
        // that must not crash on ill-shaped input.
        if (newConcurrencyLevel <= 0 || newConcurrencyLevel == this.concurrencyLevel) {
            return;
        }
        if (semaphore == null) {
            LOG.log(
                    Level.WARNING, () -> "Cannot raise concurrency on an executor that started with level 0");
            return;
        }
        if (newConcurrencyLevel < this.concurrencyLevel) {
            // Lowering atomically would require interrupting in-flight tasks; the
            // pre-existing drainPermits+release(new) dance leaked permits when
            // in-flight commands released their permit back. Until a safe
            // lower-permits primitive is added, log and skip rather than throw.
            LOG.log(
                    Level.WARNING,
                    () -> "Lowering concurrency from "
                            + this.concurrencyLevel
                            + " to "
                            + newConcurrencyLevel
                            + " is not yet supported on a started executor; ignored.");
            return;
        }
        int delta = newConcurrencyLevel - this.concurrencyLevel;
        this.concurrencyLevel = newConcurrencyLevel;
        semaphore.release(delta);
        LOG.log(Level.INFO, () -> "Handler concurrency adjusted to: " + newConcurrencyLevel);
    }

    /**
     * Indicates whether this executor is still accepting work.
     *
     * @return {@code true} when the executor has not been closed
     */
    public boolean isRunning() {
        return running;
    }

    /** {@code true} for the fire-and-forget executor. */
    boolean isVoidPath() {
        return voidPath;
    }

    private Class<?> resolveOwnerClass() {
        Class<?> handlerClass = outerHandler.getClass();
        if (handlerClass.isAnonymousClass()) {
            handlerClass = handlerClass.getSuperclass();
        } else if (handlerClass.isMemberClass()) {
            handlerClass = handlerClass.getEnclosingClass();
        }
        return handlerClass;
    }

    // ThreadContext bookkeeping — void path only.
    //
    // Each task allocates a fresh ThreadContext bound into CURRENT_TC for the duration of the
    // task. Nested synchronous dispatches see the outer context via the lexical scope; forked
    // virtual threads capture the parent through the submitting lambda's closure and rebind on
    // the worker. There is no shared mutable state and no self-parent path.

    private Runnable wrapWithThreadContext(Runnable task, @Nullable ThreadContext parentContext) {
        return () -> {
            ThreadContext newContext =
                    new ThreadContext(Thread.currentThread().threadId(), TaskType.COMMAND, parentContext);
            if (LOG.isLoggable(Level.DEBUG)) {
                LOG.log(Level.DEBUG, () -> "Task carrier threadId=" + newContext.threadId());
            }
            ScopedValue.where(CURRENT_TC, newContext)
                    .run(
                         () -> {
                             try {
                                 task.run();
                             } catch (Exception ex) {
                                 // notifyUncaughtException recurses up the parent chain and rethrows at the
                                 // root, escaping this catch so the carrier strategy (or queue worker) sees the
                                 // failure. We log here only when a non-RuntimeException slipped through wrap
                                 // semantics; the rethrow path is observed by the outer strategy.
                                 LOG.log(Level.ERROR, () -> "Uncaught exception during task execution", ex);
                                 newContext.notifyUncaughtException(toRuntime(ex));
                             }
                         });
        };
    }

    private static RuntimeException toRuntime(Exception ex) {
        return (ex instanceof RuntimeException re) ? re : new RuntimeException(ex);
    }
}

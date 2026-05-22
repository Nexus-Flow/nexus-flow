package net.nexus_flow.core.runtime;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.cqrs.command.CommandExecutorServiceFactory;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.cqrs.query.QueryBus;
import net.nexus_flow.core.outbox.OutboxConfig;
import net.nexus_flow.core.outbox.OutboxWorker;
import net.nexus_flow.core.runtime.dispatch.DispatchInterceptor;
import net.nexus_flow.core.runtime.dispatch.HandlerConcurrencyGate;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link FlowRuntime} implementation.
 *
 * <p>Every bus and registry is constructed in this constructor and owned exclusively by this
 * runtime. Two {@code DefaultFlowRuntime} instances in the same JVM share no mutable state: handler
 * registrations, listener tables, interceptor chains and the VT executor are all per-instance.
 *
 * <p>Lifecycle: {@link #close()} is strict and idempotent. It drains the buses in reverse order of
 * creation (events &rarr; queries &rarr; commands), then shuts down the runtime-owned VT executor.
 * After close, every {@link CommandBus}/{@link QueryBus}/{@link EventBus} accessor throws {@link
 * IllegalStateException} — including the typed-result {@code dispatchAndReturnResult} path, which
 * short-circuits <em>before</em> materialising the {@link DispatchInterceptor} onion.
 */
final class DefaultFlowRuntime implements FlowRuntime {

    private static final Logger LOG = System.getLogger(DefaultFlowRuntime.class.getName());

    private final CommandBus                                      commandBus;
    private final QueryBus                                        queryBus;
    private final EventBus                                        eventBus;
    private final ErrorPolicy                                     errorPolicy;
    private final ExecutionMode                                   executionMode;
    private final Duration                                        shutdownTimeout;
    private final ExecutorService                                 executor;
    private final HandlerConcurrencyGate                          concurrencyGate;
    private final ExecutionStrategy                               strategy;
    private final List<DispatchInterceptor>                       interceptors;
    private final @Nullable OutboxConfig                          outboxConfig;
    private final @Nullable OutboxWorker                          outboxWorker;
    private final net.nexus_flow.core.observability.Observability observability;
    private final int                                             eventDrainMaxDepth;
    private final AtomicBoolean                                   closed = new AtomicBoolean(false);

    /**
     * Convenience constructor that uses the system UTC clock and the no-op observability adapter.
     * Delegates to the full 9-argument constructor.
     *
     * @param errorPolicy              default failure-handling policy; never {@code null}
     * @param executionMode            default execution mode; never {@code null}
     * @param shutdownTimeout          grace period for {@link #close}; never {@code null} or negative
     * @param explicitStrategy         optional explicit strategy; {@code null} derives it from {@code
     *     executionMode}           and the runtime-owned executor
     * @param interceptors             frozen interceptor onion; never {@code null}
     * @param outboxConfig             optional durable outbox wiring; {@code null} disables outbox
     * @param parallelListenersEnabled whether listeners opted in to parallel fan-out
     */
    DefaultFlowRuntime(
            ErrorPolicy errorPolicy,
            ExecutionMode executionMode,
            Duration shutdownTimeout,
            @Nullable ExecutionStrategy explicitStrategy,
            List<DispatchInterceptor> interceptors,
            @Nullable OutboxConfig outboxConfig,
            boolean parallelListenersEnabled) {
        this(
             errorPolicy,
             executionMode,
             shutdownTimeout,
             explicitStrategy,
             interceptors,
             outboxConfig,
             parallelListenersEnabled,
             java.time.Clock.systemUTC(),
             net.nexus_flow.core.observability.Observability.NO_OP,
             FlowRuntime.DEFAULT_EVENT_DRAIN_MAX_DEPTH);
    }

    /**
     * Convenience constructor that uses the no-op observability adapter. Delegates to the full
     * 9-argument constructor.
     *
     * @param errorPolicy              default failure-handling policy; never {@code null}
     * @param executionMode            default execution mode; never {@code null}
     * @param shutdownTimeout          grace period for {@link #close}; never {@code null} or negative
     * @param explicitStrategy         optional explicit strategy; {@code null} derives it from {@code
     *     executionMode}           and the runtime-owned executor
     * @param interceptors             frozen interceptor onion; never {@code null}
     * @param outboxConfig             optional durable outbox wiring; {@code null} disables outbox
     * @param parallelListenersEnabled whether listeners opted in to parallel fan-out
     * @param clock                    injectable clock used for event-bus and dead-letter envelope timestamps
     */
    DefaultFlowRuntime(
            ErrorPolicy errorPolicy,
            ExecutionMode executionMode,
            Duration shutdownTimeout,
            @Nullable ExecutionStrategy explicitStrategy,
            List<DispatchInterceptor> interceptors,
            @Nullable OutboxConfig outboxConfig,
            boolean parallelListenersEnabled,
            java.time.Clock clock) {
        this(
             errorPolicy,
             executionMode,
             shutdownTimeout,
             explicitStrategy,
             interceptors,
             outboxConfig,
             parallelListenersEnabled,
             clock,
             net.nexus_flow.core.observability.Observability.NO_OP,
             FlowRuntime.DEFAULT_EVENT_DRAIN_MAX_DEPTH);
    }

    /**
     * Pre-eventDrainMaxDepth convenience overload. Delegates to the full primary constructor with
     * {@link FlowRuntime#DEFAULT_EVENT_DRAIN_MAX_DEPTH}.
     */
    DefaultFlowRuntime(
            ErrorPolicy errorPolicy,
            ExecutionMode executionMode,
            Duration shutdownTimeout,
            @Nullable ExecutionStrategy explicitStrategy,
            List<DispatchInterceptor> interceptors,
            @Nullable OutboxConfig outboxConfig,
            boolean parallelListenersEnabled,
            java.time.Clock clock,
            net.nexus_flow.core.observability.Observability observability) {
        this(
             errorPolicy,
             executionMode,
             shutdownTimeout,
             explicitStrategy,
             interceptors,
             outboxConfig,
             parallelListenersEnabled,
             clock,
             observability,
             FlowRuntime.DEFAULT_EVENT_DRAIN_MAX_DEPTH);
    }

    /**
     * Primary constructor. All buses and registries are created here and owned exclusively by this
     * instance. No shared global state is mutated.
     *
     * @param errorPolicy              default failure-handling policy; never {@code null}
     * @param executionMode            default execution mode; never {@code null}
     * @param shutdownTimeout          grace period for {@link #close}; never {@code null} or negative
     * @param explicitStrategy         optional explicit strategy; {@code null} derives it from {@code
     *     executionMode}           and the runtime-owned executor at construction time
     * @param interceptors             frozen interceptor onion (already a defensive copy from the builder); never
     *                                 {@code null}
     * @param outboxConfig             optional durable outbox wiring; {@code null} disables outbox
     * @param parallelListenersEnabled whether event-bus listeners opted in to parallel fan-out
     * @param clock                    injectable clock propagated to the event bus and listener executors
     * @param observability            pluggable observability sinks; never {@code null} (use {@code NO_OP} for
     *                                 no-op)
     */
    DefaultFlowRuntime(
            ErrorPolicy errorPolicy,
            ExecutionMode executionMode,
            Duration shutdownTimeout,
            @Nullable ExecutionStrategy explicitStrategy,
            List<DispatchInterceptor> interceptors,
            @Nullable OutboxConfig outboxConfig,
            boolean parallelListenersEnabled,
            java.time.Clock clock,
            net.nexus_flow.core.observability.Observability observability,
            int eventDrainMaxDepth) {
        if (eventDrainMaxDepth < 1) {
            throw new IllegalArgumentException(
                    "eventDrainMaxDepth must be >= 1: " + eventDrainMaxDepth);
        }
        this.eventDrainMaxDepth = eventDrainMaxDepth;
        this.errorPolicy        = Objects.requireNonNull(errorPolicy, "errorPolicy");
        this.executionMode      = Objects.requireNonNull(executionMode, "executionMode");
        this.shutdownTimeout    = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        Objects.requireNonNull(clock, "clock");
        this.observability = Objects.requireNonNull(observability, "observability");
        // own the executor outright (no more factory cache).
        this.executor        = CommandExecutorServiceFactory.createExecutor("vthread");
        this.concurrencyGate = new HandlerConcurrencyGate();
        // strategy wiring (unchanged).
        this.strategy =
                explicitStrategy != null ? explicitStrategy : ExecutionStrategy.fromMode(executionMode, this.executor);
        // freeze the interceptor onion at build time.
        this.interceptors = List.copyOf(Objects.requireNonNull(interceptors, "interceptors"));

        // build the per-runtime bus graph. Order matters:
        // the EventBus has no dependencies; the CommandBus needs the
        // EventBus (post-handler fan-out) and a back-reference to this
        // runtime (strategy resolution); the QueryBus is independent.
        this.eventBus   = EventBus.newInstance(parallelListenersEnabled, clock, observability);
        this.commandBus = CommandBus.newInstance(eventBus, executor, this);
        this.queryBus   = QueryBus.newInstance();

        // install the outbox config last (so the worker has
        // both buses available) and spin up the carrier-thread worker if
        // present. The worker is a no-op until rows land in storage.
        this.outboxConfig = outboxConfig;
        if (outboxConfig != null) {
            this.outboxWorker = new OutboxWorker(outboxConfig, eventBus, errorPolicy);
            if (outboxConfig.autoStartWorker()) {
                this.outboxWorker.start();
            }
        } else {
            this.outboxWorker = null;
        }
    }

    @Override
    public CommandBus commands() {
        ensureOpen();
        return commandBus;
    }

    @Override
    public QueryBus queries() {
        ensureOpen();
        return queryBus;
    }

    @Override
    public EventBus events() {
        ensureOpen();
        return eventBus;
    }

    @Override
    public ErrorPolicy errorPolicy() {
        return errorPolicy;
    }

    @Override
    public ExecutionMode executionMode() {
        return executionMode;
    }

    @Override
    public ExecutionStrategy executionStrategy() {
        ensureOpen();
        return strategy;
    }

    @Override
    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    @Override
    public int eventDrainMaxDepth() {
        return eventDrainMaxDepth;
    }

    @Override
    public ExecutorService executor() {
        ensureOpen();
        return executor;
    }

    @Override
    public HandlerConcurrencyGate concurrencyGate() {
        ensureOpen();
        return concurrencyGate;
    }

    @Override
    public List<DispatchInterceptor> interceptors() {
        // Intentionally accessible even after close(): the list is
        // immutable and contains no live resources. Dispatch entry
        // points short-circuit on closed before consulting this list,
        // so a post-close caller cannot accidentally drive the onion.
        return interceptors;
    }

    @Override
    public Optional<OutboxConfig> outbox() {
        return Optional.ofNullable(outboxConfig);
    }

    @Override
    public net.nexus_flow.core.observability.Observability observability() {
        return observability;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean awaitOutboxIdle(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        if (outboxWorker == null) {
            return true;
        }
        return outboxWorker.awaitIdle(timeout);
    }

    @Override
    public void close() {
        // shutdownTimeout is guaranteed non-null by the constructor (Objects.requireNonNull).
        shutdown(shutdownTimeout);
    }

    @Override
    public void shutdown(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        // Idempotent: only the first transition triggers shutdown work
        // (RuntimeIdempotentCloseTest pins this invariant).
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // stop the outbox worker BEFORE the event bus so a
        // dispatch in progress can complete and mark its row, and so no
        // new claims happen against a closing bus. When OutboxConfig.drainOnShutdown() is true, the
        // worker also pulls every eligible row through drainOnce() before stopping.
        if (outboxWorker != null) {
            try {
                outboxWorker.shutdown();
            } catch (RuntimeException shutdownEx) {
                LOG.log(
                        Level.WARNING,
                        () -> "Outbox worker shutdown failed during runtime close; continuing lifecycle shutdown",
                        shutdownEx);
            }
        }
        // Strict lifecycle: drain buses in reverse order of creation so listeners do not
        // observe a dispatch from a half-closed sibling bus. Each drain is best-effort;
        // we never let a publisher's failure stop the executor shutdown.
        try {
            eventBus.closeAll();
        } catch (RuntimeException eventBusEx) {
            LOG.log(
                    Level.WARNING,
                    () -> "Event bus close failed during runtime shutdown; continuing with remaining resources",
                    eventBusEx);
        }
        // QueryBus has no resources to release beyond its registry,
        // which is GC-reachable only through the bus reference.
        try {
            commandBus.closeAll();
        } catch (RuntimeException commandBusEx) {
            LOG.log(
                    Level.WARNING,
                    () -> "Command bus close failed during runtime shutdown; continuing with executor shutdown",
                    commandBusEx);
        }
        // Use the per-call timeout (which may override the builder-time shutdownTimeout)
        // for the runtime-owned executor.
        CommandExecutorServiceFactory.shutdown(executor, timeout);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new net.nexus_flow.core.runtime.exceptions.FlowRuntimeClosedException();
        }
    }
}

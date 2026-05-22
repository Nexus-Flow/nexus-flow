package net.nexus_flow.core.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.cqrs.query.QueryBus;
import net.nexus_flow.core.outbox.OutboxConfig;
import net.nexus_flow.core.runtime.dispatch.DispatchInterceptor;
import net.nexus_flow.core.runtime.dispatch.HandlerConcurrencyGate;
import org.jspecify.annotations.Nullable;

/**
 * Top-level entry point of the Nexus Flow runtime.
 *
 * <p>Owns the lifecycle of every shared resource (executors, registries, scope state). Instances
 * are created through {@link Builder} and are intended to live for the lifetime of an application
 * or a test fixture.
 *
 * <p>Key responsibilities:
 *
 * <ul>
 * <li><strong>Executor ownership</strong> — the shared virtual-thread executor is acquired in the
 * constructor and shut down on {@link #close()} with a configurable graceful timeout (see
 * {@link Builder#shutdownTimeout(Duration)}).
 * <li><strong>Per-handler concurrency gate</strong> — a single {@link HandlerConcurrencyGate}
 * keyed by command/event type replaces the per-handler executor used in earlier designs. The
 * shared VT executor remains the actual carrier.
 * </ul>
 *
 * <p>Bus accessors ({@link #commands()}, {@link #queries()}, {@link #events()}) return
 * <strong>per-runtime instances</strong>. Two runtimes in the same JVM coexist without sharing
 * handler registries, executor pools or interceptor chains.
 *
 * <p>{@link AutoCloseable#close()} is overridden to <em>not</em> declare checked exceptions:
 * shutting the runtime down must be safe to invoke from {@code try}-with-resources without
 * surfacing infrastructure failures to callers.
 */
public interface FlowRuntime extends AutoCloseable {

    /** Default graceful shutdown timeout. */
    Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Bus for command dispatch (fire-and-forget and request/response).
     *
     * @throws IllegalStateException when called after {@link #close()}.
     */
    CommandBus commands();

    /**
     * Bus for synchronous, read-only query dispatch.
     *
     * @throws IllegalStateException when called after {@link #close()}.
     */
    QueryBus queries();

    /**
     * Bus for domain-event publication.
     *
     * @throws IllegalStateException when called after {@link #close()}.
     */
    EventBus events();

    /** Default {@link ErrorPolicy} for dispatches that do not specify one. */
    ErrorPolicy errorPolicy();

    /** Default {@link ExecutionMode} for dispatches that do not specify one. */
    ExecutionMode executionMode();

    /**
     * Default {@link ExecutionStrategy} for dispatches that do not specify one. When the runtime is
     * built without an explicit strategy override, it is derived from {@link #executionMode()} and
     * {@link #executor()} via {@link ExecutionStrategy#fromMode(ExecutionMode,
     * java.util.concurrent.ExecutorService)}.
     *
     * <p>Per-handler {@code *HandlerExecutor} instances may still derive their own strategy from each
     * handler's saga flag. The runtime-level strategy is exposed here so tests can inject mock
     * implementations and so per-handler routing can be layered on top.
     *
     * @throws IllegalStateException when called after {@link #close()}.
     */
    ExecutionStrategy executionStrategy();

    /** Graceful shutdown timeout honored by {@link #close()}. */
    Duration shutdownTimeout();

    /**
     * Runtime-owned virtual-thread executor used by the structured dispatcher to fork sibling
     * handlers.
     *
     * @throws IllegalStateException when called after {@link #close()}.
     */
    ExecutorService executor();

    /**
     * Per-handler concurrency gate. Exposed so the structured dispatcher can serialize/parallelize
     * handler invocations according to {@code handler.concurrencyLevel}.
     *
     * @throws IllegalStateException when called after {@link #close()}.
     */
    HandlerConcurrencyGate concurrencyGate();

    /**
     * Ordered list of {@link DispatchInterceptor}s registered through {@link
     * Builder#interceptor(DispatchInterceptor)}.
     *
     * <p>The list is returned in <strong>registration order</strong>: the first interceptor is the
     * outermost shell of the onion. The returned list is immutable; the runtime owns it for the
     * duration of its lifetime.
     */
    List<DispatchInterceptor> interceptors();

    /**
     * optional outbox wiring. When present, the command bus appends drained events to {@link
     * OutboxConfig#storage()} in the same logical transaction as the aggregate persistence, and (if
     * {@link OutboxConfig#useOutboxFanOut()} is true) skips the inline event-bus fan-out so the
     * {@code OutboxWorker} becomes the sole publisher. {@link Optional#empty()} means inline fan-out
     * only (no outbox writes).
     */
    Optional<OutboxConfig> outbox();

    /**
     * observability sinks installed via {@link
     * Builder#observability(net.nexus_flow.core.observability.Observability)}. Never {@code null}:
     * when no adapter is wired this returns {@link
     * net.nexus_flow.core.observability.Observability#NO_OP}.
     */
    net.nexus_flow.core.observability.Observability observability();

    /** {@code true} after {@link #close()} has been observed by some thread. */
    boolean isClosed();

    /**
     * Release every resource owned by this runtime. Idempotent: calling {@code close()} a second time
     * is a no-op.
     *
     * <p>Shuts down the runtime-owned virtual-thread executor via {@link
     * net.nexus_flow.core.cqrs.command.CommandExecutorServiceFactory#shutdown(ExecutorService,
     * Duration)}. In-flight tasks are given up to {@link #shutdownTimeout()} to complete; remaining
     * tasks are interrupted via {@link ExecutorService#shutdownNow()}.
     */
    @Override
    void close();

    /**
     * graceful shutdown with a per-call timeout that overrides {@link #shutdownTimeout()} for this
     * invocation. Use this overload from a lifecycle controller that wants to bound the wait
     * independently of the builder-time setting (e.g. a Kubernetes pre-stop hook that has a strict
     * SIGTERM grace period).
     *
     * <p>Idempotent: calling {@code shutdown(...)} or {@link #close()} a second time is a no-op.
     *
     * @param timeout grace period; must be non-null and non-negative. {@link Duration#ZERO} causes
     *                immediate {@code shutdownNow} on the runtime-owned executor.
     */
    void shutdown(Duration timeout);

    /** Start a new {@link Builder} with default settings. */
    static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link FlowRuntime} instances. */
    final class Builder {

        private ErrorPolicy   errorPolicy     = ErrorPolicy.failFast();
        private ExecutionMode executionMode   = ExecutionMode.synchronous();
        private Duration      shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

        /**
         * Optional explicit strategy override. When {@code null}, {@link DefaultFlowRuntime} derives
         * one via {@link ExecutionStrategy#fromMode(ExecutionMode,
         * java.util.concurrent.ExecutorService)} using the runtime-owned executor.
         */
        private @Nullable ExecutionStrategy strategy;

        /**
         * Ordered list of dispatch interceptors. Empty by default — zero interceptors means the
         * dispatcher runs without any cross-cutting wrappers. New interceptors are appended; the first
         * registered is the outermost shell of the onion.
         */
        private final List<DispatchInterceptor> interceptors = new ArrayList<>();

        /**
         * optional outbox configuration. {@code null} leaves the kill-switch off (no outbox writes,
         * inline fan-out unchanged).
         */
        private @Nullable OutboxConfig outbox;

        /**
         * opt-in parallel listener fan-out. Off by default; when on, the {@link EventBus} runs all
         * listeners of a given event concurrently <em>iff every listener has declared itself {@code
         * EventListener#parallelSafe() == true}</em>.
         */
        private boolean parallelListeners;

        /**
         * injectable wall-clock propagated end-to-end (event bus → listener executor → dead-letter
         * envelopes). Defaults to {@link java.time.Clock#systemUTC()}; adapter modules (Spring /
         * Quarkus / Micronaut) override it with the framework-supplied {@code Clock} bean so test
         * harnesses get deterministic timestamps without monkey-patching {@code Instant.now}.
         */
        private java.time.Clock clock = java.time.Clock.systemUTC();

        /**
         * pluggable observability sinks. Defaults to {@link
         * net.nexus_flow.core.observability.Observability#NO_OP} so {@code core} stays dependency-free
         * and pays zero cost when no adapter is wired. Spring / Quarkus / OpenTelemetry adapter modules
         * build an {@code Observability} from their native registries (MeterRegistry + Micrometer
         * Tracing, OTel Meter + Tracer, …) and inject it here.
         */
        private net.nexus_flow.core.observability.Observability observability =
                net.nexus_flow.core.observability.Observability.NO_OP;

        /**
         * Auto-registration queue: handlers / listeners staged via {@link #handler(Object)} or
         * {@link #handlers(Object...)} are kept here and registered against the buses after the
         * runtime is constructed in {@link #build()}. Order preserved so cross-handler
         * dependencies (e.g. listener-order requirements) stay deterministic.
         */
        private final List<Object> pendingHandlerRegistrations = new ArrayList<>();

        Builder() {
        }

        /** Override the default {@link ErrorPolicy}. Defaults to {@link ErrorPolicy#failFast()}. */
        public Builder defaultErrorPolicy(ErrorPolicy errorPolicy) {
            this.errorPolicy = java.util.Objects.requireNonNull(errorPolicy, "errorPolicy");
            return this;
        }

        /**
         * Override the default {@link ExecutionMode}. Defaults to {@link ExecutionMode#synchronous()}.
         */
        public Builder defaultExecutionMode(ExecutionMode executionMode) {
            this.executionMode = java.util.Objects.requireNonNull(executionMode, "executionMode");
            return this;
        }

        /**
         * Override the graceful shutdown timeout honored by {@link FlowRuntime#close()}. Must be
         * non-null and non-negative; {@link Duration#ZERO} causes an immediate {@code shutdownNow}.
         * Defaults to {@link FlowRuntime#DEFAULT_SHUTDOWN_TIMEOUT}.
         */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            java.util.Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
            if (shutdownTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "shutdownTimeout must not be negative: " + shutdownTimeout);
            }
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        /**
         * Override the default {@link ExecutionStrategy}. Primarily intended for tests that need to
         * inject a recording or no-op strategy; production callers should prefer {@link
         * #defaultExecutionMode(ExecutionMode)} so the runtime can wire the strategy itself.
         *
         * <p>The injected strategy is exposed verbatim through {@link FlowRuntime#executionStrategy()};
         * the runtime never mutates or wraps it. When no strategy is set, the runtime derives one from
         * {@link #executionMode} and the runtime-owned VT executor at build time.
         */
        public Builder strategy(ExecutionStrategy strategy) {
            this.strategy = java.util.Objects.requireNonNull(strategy, "strategy");
            return this;
        }

        /**
         * Append a {@link DispatchInterceptor} to the onion.
         *
         * <p>Order matters: the <strong>first</strong> interceptor registered is the outermost shell of
         * the onion. For a registration sequence {@code A, B, C} a dispatch is observed as {@code A_pre
         * → B_pre → C_pre → handler → C_post → B_post → A_post}.
         *
         * <p>Interceptor instances are shared across every dispatch of the built runtime and MUST be
         * thread-safe.
         */
        public Builder interceptor(DispatchInterceptor interceptor) {
            this.interceptors.add(java.util.Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /**
         * install an {@link OutboxConfig}. Passing {@code null} resets to the default (kill-switch
         * off).
         */
        public Builder outbox(@Nullable OutboxConfig outbox) {
            this.outbox = outbox;
            return this;
        }

        /**
         * enable opt-in parallel listener fan-out. When {@code true}, the {@link EventBus} runs each
         * event's listeners concurrently <strong>only when every listener of that concrete event class
         * has overridden {@code EventListener#parallelSafe()} to {@code true}</strong>. Any
         * non-parallel-safe listener for the event keeps the sequential, registration-order semantics.
         *
         * <p>Defaults to {@code false} (always sequential).
         */
        public Builder parallelListeners(boolean enabled) {
            this.parallelListeners = enabled;
            return this;
        }

        /**
         * inject a custom {@link java.time.Clock}. Defaults to {@link java.time.Clock#systemUTC()}. The
         * clock is propagated to the event bus and to every {@code ListenerExecutor} for use in
         * dead-letter envelopes and any other timestamp the framework needs to stamp internally.
         * Adapter modules (Spring / Quarkus / Micronaut) typically wire the framework-supplied {@code
         * Clock} bean here.
         *
         * <p><strong>Note.</strong> The clock is <em>not</em> applied to {@code
         * AbstractDomainEvent#timestamp} or to the {@code Command} / {@code Query} default timestamp:
         * those are part of the frozen wire format and remain at {@code Instant.now()}. The Clock SPI
         * targets framework-internal timestamps that adapter authors need to control for testing.
         */
        public Builder clock(java.time.Clock clock) {
            this.clock = java.util.Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * install pluggable observability sinks (metrics + tracing). Defaults to {@link
         * net.nexus_flow.core.observability.Observability#NO_OP}.
         *
         * <p>This is the documented hook for OpenTelemetry, Micrometer, Datadog and similar adapter
         * modules. The framework uses the sinks to record listener-level signals (retries, dead-letter
         * routing, rate-limit drops, deduplicator hits, parallel fan-out timing) that fall
         * <em>below</em> the {@link DispatchInterceptor} onion and therefore can't be captured by
         * interceptors alone.
         */
        public Builder observability(net.nexus_flow.core.observability.Observability observability) {
            this.observability = java.util.Objects.requireNonNull(observability, "observability");
            return this;
        }

        /**
         * Stage a single handler or listener for automatic registration once the runtime is built.
         *
         * <p>Accepted types (any other shape fails {@link IllegalArgumentException} at build time):
         *
         * <ul>
         * <li>{@link net.nexus_flow.core.cqrs.command.NoReturnCommandHandler} —
         * registered on {@link FlowRuntime#commands()}.
         * <li>{@link net.nexus_flow.core.cqrs.command.ReturnCommandHandler} —
         * registered on {@link FlowRuntime#commands()}.
         * <li>{@link net.nexus_flow.core.cqrs.event.DomainEventListener} —
         * registered on {@link FlowRuntime#events()}.
         * <li>{@link net.nexus_flow.core.cqrs.query.AbstractQueryHandler} —
         * registered on {@link FlowRuntime#queries()}.
         * </ul>
         *
         * <p>The runtime stays the only DI root — handlers/listeners are passed as concrete
         * instances; the framework does NOT do field/constructor injection. Wire dependencies
         * yourself (or via an external container) before passing the instance.
         */
        public Builder handler(Object handler) {
            java.util.Objects.requireNonNull(handler, "handler");
            assertSupportedHandler(handler);
            this.pendingHandlerRegistrations.add(handler);
            return this;
        }

        /**
         * Batch variant of {@link #handler(Object)} preserving registration order.
         */
        public Builder handlers(Object... handlers) {
            java.util.Objects.requireNonNull(handlers, "handlers");
            for (Object h : handlers) {
                handler(h);
            }
            return this;
        }

        private static void assertSupportedHandler(Object handler) {
            if (handler instanceof net.nexus_flow.core.cqrs.command.NoReturnCommandHandler<?> || handler instanceof net.nexus_flow.core.cqrs.command.ReturnCommandHandler<?, ?> || handler instanceof net.nexus_flow.core.cqrs.event.DomainEventListener<?> || handler instanceof net.nexus_flow.core.cqrs.query.AbstractQueryHandler<?, ?>) {
                return;
            }
            throw new IllegalArgumentException(
                    "Unsupported handler/listener type: " + handler.getClass().getName()
                            + " — accepted: NoReturnCommandHandler, ReturnCommandHandler,"
                            + " DomainEventListener, AbstractQueryHandler");
        }

        /** Build a fresh runtime instance. The returned runtime is open. */
        public FlowRuntime build() {
            FlowRuntime runtime = new DefaultFlowRuntime(
                    errorPolicy,
                    executionMode,
                    shutdownTimeout,
                    strategy,
                    List.copyOf(interceptors),
                    outbox,
                    parallelListeners,
                    clock,
                    observability);
            for (Object h : pendingHandlerRegistrations) {
                registerAutoHandler(runtime, h);
            }
            return runtime;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void registerAutoHandler(FlowRuntime runtime, Object handler) {
            switch (handler) {
                case net.nexus_flow.core.cqrs.command.NoReturnCommandHandler<?> h  ->
                     runtime.commands().register((net.nexus_flow.core.cqrs.command.NoReturnCommandHandler) h);
                case net.nexus_flow.core.cqrs.command.ReturnCommandHandler<?, ?> h ->
                     runtime.commands().register((net.nexus_flow.core.cqrs.command.ReturnCommandHandler) h);
                case net.nexus_flow.core.cqrs.event.DomainEventListener<?> l       ->
                     runtime.events().register((net.nexus_flow.core.cqrs.event.DomainEventListener) l);
                case net.nexus_flow.core.cqrs.query.AbstractQueryHandler<?, ?> q   ->
                     runtime.queries().register((net.nexus_flow.core.cqrs.query.AbstractQueryHandler) q);
                default                                                            ->
                        throw new IllegalStateException(
                                "stage queue contained an unsupported handler type: "
                                        + handler.getClass().getName()
                                        + " — assertSupportedHandler should have rejected it");
            }
        }
    }
}

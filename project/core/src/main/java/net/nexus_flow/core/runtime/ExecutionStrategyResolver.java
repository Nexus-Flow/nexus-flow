package net.nexus_flow.core.runtime;

import java.util.Objects;
import net.nexus_flow.core.cqrs.command.CommandHandler;
import net.nexus_flow.core.cqrs.command.CommandSettings;

/**
 * Per-handler {@link ExecutionStrategy} resolution.
 *
 * <p>The runtime-level strategy was historically derived only from {@code handler.isSagaEnabled()},
 * leaving {@link CommandSettings#executionMode()} unused. This resolver elevates the execution mode
 * field to first-class importance so handlers can opt into {@link
 * ExecutionMode#asynchronousInMemory()} or durable-async modes independently of their saga flag.
 *
 * <h2>Precedence</h2>
 *
 * The {@link #resolveStrategy(CommandHandler, FlowRuntime)} method applies the following rules, in
 * order:
 *
 * <ol>
 * <li>If {@code handler.getCommandSettings().executionMode()} is <em>non-empty</em>, that mode
 * wins. The saga flag and the runtime default are ignored.
 * <li>Otherwise, {@code handler.isSagaEnabled()} is the legacy shortcut: {@code true} maps to
 * {@link ExecutionMode#synchronous()} (preserving the legacy "saga handlers run inline on the
 * caller" guarantee).
 * <li>Otherwise, the runtime default ({@link FlowRuntime#executionMode()}) is used.
 * </ol>
 *
 * <p>The resolved {@link ExecutionMode} is then turned into a concrete {@link ExecutionStrategy}
 * through an <strong>exhaustive switch</strong> with no {@code default} branch — adding a new
 * permit on {@link ExecutionMode} must surface here at compile time. The mapping is intentionally
 * identical to {@link ExecutionStrategy#fromMode(ExecutionMode,
 * java.util.concurrent.ExecutorService)}, but lives in this resolver so the handler-level wiring is
 * a single call from the executors.
 *
 * <h2>Durable async</h2>
 *
 * <p>When the resolved mode is {@link ExecutionMode.AsynchronousDurable} the resolver enforces the
 * binding rule: the active {@link FlowRuntime} MUST carry an {@link FlowRuntime#outbox() outbox}
 * configuration. If the runtime has no outbox bound the resolver throws {@link
 * IllegalStateException} with a message that names the missing builder call — fail-fast at resolve
 * time rather than at the first {@code task.run()} or, worse, silently degrading to non-durable
 * Inline execution.
 *
 * <p>Once the precondition holds, the resolver returns a fresh {@link
 * ExecutionStrategy.AsynchronousDurable}, whose execution semantics are Inline. The durable side
 * effect (outbox append) is performed by the post-handler event drain ({@code
 * HandlerEventDrain#drain}) using the runtime's outbox binding — see the {@code
 * AsynchronousDurable} Javadoc on {@link ExecutionStrategy} for the full split-of-responsibility
 * argument.
 *
 * <h2>Thread safety</h2>
 *
 * The resolver is stateless: every call walks {@link CommandHandler#getCommandSettings()} freshly,
 * so handlers that reconfigure their settings between dispatches see the updated value on the next
 * dispatch (no per-executor caching is required for correctness — see {@code *HandlerExecutor} for
 * the caching policy).
 */
public final class ExecutionStrategyResolver {

    private ExecutionStrategyResolver() {
        // utility class — instances would carry no state.
    }

    /**
     * Resolve the {@link ExecutionStrategy} for a single dispatch of {@code handler} against {@code
     * runtime}. See the class javadoc for the precedence rules.
     *
     * @param handler handler about to be dispatched; never {@code null}. {@link
     *                CommandHandler#getCommandSettings()} and {@link CommandHandler#isSagaEnabled()} are read.
     * @param runtime active {@link FlowRuntime}; never {@code null}. {@link
     *                FlowRuntime#executionMode()} is the fallback default, and {@link FlowRuntime#executor()} is
     *                the carrier for {@link ExecutionMode#asynchronousInMemory()}.
     * @return a fresh strategy instance — callers may cache it but the resolver itself never
     *         memoises.
     * @throws IllegalStateException if the resolved mode is {@link ExecutionMode.AsynchronousDurable}
     *                               but {@link FlowRuntime#outbox()} is empty. Durable handlers declare an outbox contract;
     *                               absent the outbox there is no sink to make the dispatch durable, so the resolver refuses to
     *                               hand back a strategy that would silently degrade to non-durable Inline execution.
     */
    public static ExecutionStrategy resolveStrategy(
            CommandHandler<?, ?, ?> handler, FlowRuntime runtime) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(runtime, "runtime");

        ExecutionMode resolved = resolveMode(handler, runtime);

        // Exhaustive switch on the sealed ExecutionMode — no default
        // branch. A new mode permit must surface here at compile time.
        return switch (resolved) {
            case ExecutionMode.Synchronous _          -> new ExecutionStrategy.Inline();
            case ExecutionMode.AsynchronousInMemory _ ->
                 new ExecutionStrategy.VirtualThread(runtime.executor());
            case ExecutionMode.AsynchronousDurable _  -> {
                if (runtime.outbox().isEmpty()) {
                    throw new net.nexus_flow.core.runtime.exceptions.MissingOutboxBindingException();
                }
                yield new ExecutionStrategy.AsynchronousDurable();
            }
        };
    }

    /**
     * Pure {@link ExecutionMode} resolution — separated from the strategy switch so the matrix tests
     * can pin the precedence rules without instantiating a full {@link FlowRuntime}'s executor.
     * Visible for tests in the same package.
     */
    static ExecutionMode resolveMode(CommandHandler<?, ?, ?> handler, FlowRuntime runtime) {
        CommandSettings settings = handler.getCommandSettings();
        if (settings != null) {
            ExecutionMode override = settings.executionMode().orElse(null);
            if (override != null) {
                return override;
            }
        }
        return handler.isSagaEnabled() ? ExecutionMode.synchronous() : runtime.executionMode();
    }
}

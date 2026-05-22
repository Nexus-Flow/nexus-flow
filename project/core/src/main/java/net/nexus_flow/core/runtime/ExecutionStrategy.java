package net.nexus_flow.core.runtime;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import net.nexus_flow.core.runtime.dispatch.SyncDispatcher;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowCancellationException;

/**
 * Sealed dispatch policy for handler execution.
 *
 * <p>An {@code ExecutionStrategy} answers the single question <em>where does this task run?</em>:
 * inline on the caller, on the runtime-owned virtual-thread executor, or on the durable async
 * pipeline. The strategy is the only place that decides:
 *
 * <ol>
 * <li>whether to fork onto another thread,
 * <li>how to bind the {@link FlowScope#CURRENT_CONTEXT} for the dynamic extent of the task, and
 * <li>when to assert {@link ExecutionContext#throwIfCancelledOrExpired()}.
 * </ol>
 *
 * <p>Everything else — domain-event sink wiring, semaphore-gated concurrency, backoff, queue
 * draining — stays in the {@code *HandlerExecutor}. The strategy is intentionally orthogonal to
 * {@link DispatchResult} and {@link ErrorPolicy}: it receives a task already wrapped by {@link
 * SyncDispatcher#invoke}/{@code fanOut} and just decides where it runs.
 *
 * <h2>Implementations</h2>
 *
 * <ul>
 * <li>{@link Inline} — runs the task on the caller thread. Used by saga handlers and by the
 * synchronous nested-dispatch path ({@link ExecutionMode#synchronous()}).
 * <li>{@link VirtualThread} — submits the task to a runtime-owned virtual-thread executor. Used
 * by non-saga handlers and by {@link ExecutionMode#asynchronousInMemory()}.
 * <li>{@link AsynchronousDurable} — durable-async permit. Behaves like {@link Inline} from an
 * <em>execution</em> standpoint (durability is provided by the per-runtime outbox sink that
 * drains the handler's recorded events, not by the strategy itself). The strategy is a
 * distinct permit so that {@link ExecutionStrategyResolver} can attach an extra precondition
 * — handlers declaring durable mode require {@link FlowRuntime#outbox()} to be bound — and so
 * that exhaustive switches over this sealed hierarchy must reckon with the durable contract
 * explicitly.
 * </ul>
 *
 * <p>Adding a new permitted subtype is a breaking change for every exhaustive {@code switch} over
 * this hierarchy — that is exactly the goal: the compiler enforces that callers reckon with the new
 * variant.
 */
public sealed interface ExecutionStrategy
        permits ExecutionStrategy.Inline,
        ExecutionStrategy.VirtualThread,
        ExecutionStrategy.AsynchronousDurable {

    /**
     * Run a value-producing task under this strategy. Implementations MUST:
     *
     * <ul>
     * <li>call {@link ExecutionContext#throwIfCancelledOrExpired()} before submitting the task;
     * <li>bind {@code ctx} into {@link FlowScope#CURRENT_CONTEXT} for the dynamic extent of the
     * task (so handler code can call {@link FlowScope#requireCurrent()} reliably);
     * <li>propagate exceptions thrown by {@code task} VERBATIM — no {@link
     * java.util.concurrent.CompletionException} or {@link ExecutionException} wrapping.
     * </ul>
     *
     * @throws Exception whatever {@code task} throws, unwrapped.
     */
    <R> R run(Callable<R> task, ExecutionContext ctx) throws Exception;

    /**
     * Fire-and-forget variant. The same contract as {@link #run(Callable, ExecutionContext)} applies,
     * except that {@link Inline} runs synchronously on the caller while {@link VirtualThread} returns
     * as soon as the task is submitted (the runtime fan-out logic is what stitches the results back
     * together — see {@code SyncDispatcher.fanOut}).
     */
    void run(Runnable task, ExecutionContext ctx);

    /**
     * Pick the right strategy for {@code mode}. The {@code switch} is exhaustive and has no {@code
     * default} branch on purpose — a new permit on {@link ExecutionMode} must surface here at compile
     * time, mirroring {@code SyncDispatcher.fanOut} on {@link ErrorPolicy}.
     *
     * @param mode     execution mode, never {@code null}.
     * @param executor runtime-owned virtual-thread executor; required by {@link VirtualThread},
     *                 ignored by {@link Inline} and {@link AsynchronousDurable}.
     */
    static ExecutionStrategy fromMode(ExecutionMode mode, ExecutorService executor) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(executor, "executor");
        return switch (mode) {
            case ExecutionMode.Synchronous _          -> new Inline();
            case ExecutionMode.AsynchronousInMemory _ -> new VirtualThread(executor);
            // closeout — durable mode is a distinct strategy
            // permit whose execution semantics are Inline; the durable
            // <em>side effect</em> (outbox append) is performed by the
            // post-handler event drain. {@link ExecutionStrategyResolver}
            // is the single place that asserts the outbox-bound
            // precondition before this strategy is returned.
            case ExecutionMode.AsynchronousDurable _ -> new AsynchronousDurable();
        };
    }

    // ------------------------------------------------------------------
    // Inline — saga / Synchronous mode
    // ------------------------------------------------------------------

    /**
     * Runs the task on the caller thread, propagating exceptions verbatim. Equivalent to calling
     * {@code task.run()} after binding the {@link FlowScope#CURRENT_CONTEXT}.
     */
    final class Inline implements ExecutionStrategy {

        public Inline() {
            // public no-arg constructor: instantiable from tests and
            // from the Builder.strategy(...) injector.
        }

        @Override
        public <R> R run(Callable<R> task, ExecutionContext ctx) throws Exception {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(ctx, "ctx");
            ctx.throwIfCancelledOrExpired();
            // If the caller is already running with this exact context
            // bound (re-entrant case), don't open a nested binding —
            // ScopedValue forbids rebinding the same key inside the
            // same dynamic extent. Otherwise, open the binding so
            // FlowScope.requireCurrent() works inside the task.
            if (FlowScope.current().orElse(null) == ctx) {
                return task.call();
            }
            return ScopedValue.where(FlowScope.CURRENT_CONTEXT, ctx).call(task::call);
        }

        @Override
        public void run(Runnable task, ExecutionContext ctx) {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(ctx, "ctx");
            ctx.throwIfCancelledOrExpired();
            if (FlowScope.current().orElse(null) == ctx) {
                task.run();
            } else {
                FlowScope.runWithContext(ctx, task);
            }
        }

        @Override
        public String toString() {
            return "ExecutionStrategy.Inline";
        }
    }

    // ------------------------------------------------------------------
    // VirtualThread — non-saga / AsynchronousInMemory mode
    // ------------------------------------------------------------------

    /**
     * Submits the task to a runtime-owned virtual-thread executor. The {@link Callable} variant
     * {@code .get()}s the result on the caller, unwrapping {@link ExecutionException} to honor the
     * no-wrapping contract for exceptions propagated to callers. The {@link Runnable} variant is
     * fire-and-forget — the calling executor is the one that stitches results back together via the
     * structured dispatcher.
     */
    record VirtualThread(ExecutorService executor) implements ExecutionStrategy {

        public VirtualThread {
            Objects.requireNonNull(executor, "executor");
        }

        @Override
        // ExecutionException.getCause() may be a checked Exception; re-thrown verbatim below.
        //noinspection RedundantThrows
        public <R> R run(Callable<R> task, ExecutionContext ctx) throws Exception {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(ctx, "ctx");
            ctx.throwIfCancelledOrExpired();
            // double-guard: the border
            // check above runs on the caller, but the submitted task
            // can sit in the executor's queue (or wait for a virtual
            // carrier) for an unbounded amount of wall-clock time.
            // Re-checking inside the Callable closes the submit-vs-run
            // race so a cancellation raised after submit still beats
            // the user code to the punch.
            Future<R> future =
                    executor.submit(
                                    () -> ScopedValue.where(FlowScope.CURRENT_CONTEXT, ctx)
                                            .call(
                                                  () -> {
                                                      ctx.throwIfCancelledOrExpired();
                                                      return task.call();
                                                  }));
            try {
                return future.get();
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof Exception ex) {
                    throw ThrowableUtils.withSuppressed(ex, ee);
                }
                if (cause instanceof Error err) {
                    throw ThrowableUtils.withSuppressed(err, ee);
                }
                throw ThrowableUtils.withSuppressed(new RuntimeException(cause), ee);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ThrowableUtils.withSuppressed(new FlowCancellationException(), ie);
            }
        }

        @Override
        public void run(Runnable task, ExecutionContext ctx) {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(ctx, "ctx");
            ctx.throwIfCancelledOrExpired();
            // same double-guard as the Callable variant.
            // The fire-and-forget contract means we cannot surface the
            // FlowCancellationException to the caller; cancellation is enforced
            // by ensuring the user code does not run after cancellation, not by
            // propagating the exception back through to submit (which had already
            // returned).
            executor.submit(
                            () -> FlowScope.runWithContext(
                                                           ctx,
                                                           () -> {
                                                               ctx.throwIfCancelledOrExpired();
                                                               task.run();
                                                           }));
        }

        @Override
        public String toString() {
            return "ExecutionStrategy.VirtualThread[executor=" + executor + "]";
        }
    }

    // ------------------------------------------------------------------
    // AsynchronousDurable — durable-async permit
    // ------------------------------------------------------------------

    /**
     * Durable async strategy.
     *
     * <p>From an <em>execution</em> standpoint this strategy is indistinguishable from {@link
     * Inline}: the task is run synchronously on the caller thread under a {@link
     * FlowScope#CURRENT_CONTEXT} binding, propagating exceptions verbatim. The strategy is a distinct
     * permit because:
     *
     * <ol>
     * <li>{@link ExecutionStrategyResolver} attaches a precondition to durable mode — the handler's
     * runtime MUST carry an {@link FlowRuntime#outbox() outbox} binding — and surfaces a
     * self-describing failure when that invariant is broken (single point of truth, fail-fast
     * at resolve time);
     * <li>exhaustive switches over the sealed hierarchy must acknowledge the durable variant
     * explicitly (the compiler guards against silent drift);
     * <li>the post-handler event drain ({@code HandlerEventDrain#drain}) reads the runtime's outbox
     * binding and routes the recorded events through {@link
     * net.nexus_flow.core.outbox.OutboxAppender} — that is where durability is actually
     * realized, not in this strategy's {@code run(...)} body.
     * </ol>
     *
     * <p>Rationale: the strategy is a "where does it run?" answer, and durable handlers run inline
     * (the outbox append is fast and deterministic — there is no value in forking onto a virtual
     * thread). Tying durability to the strategy <em>type</em> would conflate that orthogonal axis
     * with the dispatch sink.
     */
    final class AsynchronousDurable implements ExecutionStrategy {

        public AsynchronousDurable() {
            // No-arg constructor: instantiable from tests, from
            // {@link #fromMode(ExecutionMode, ExecutorService)}, and
            // from {@link ExecutionStrategyResolver}.
        }

        @Override
        public <R> R run(Callable<R> task, ExecutionContext ctx) throws Exception {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(ctx, "ctx");
            ctx.throwIfCancelledOrExpired();
            // Mirror {@link Inline#run(Callable, ExecutionContext)}:
            // re-entrant binding is forbidden by ScopedValue, so we
            // only open a new scope when the active binding is not
            // already {@code ctx}.
            if (FlowScope.current().orElse(null) == ctx) {
                return task.call();
            }
            return ScopedValue.where(FlowScope.CURRENT_CONTEXT, ctx).call(task::call);
        }

        @Override
        public void run(Runnable task, ExecutionContext ctx) {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(ctx, "ctx");
            ctx.throwIfCancelledOrExpired();
            if (FlowScope.current().orElse(null) == ctx) {
                task.run();
            } else {
                FlowScope.runWithContext(ctx, task);
            }
        }

        @Override
        public String toString() {
            return "ExecutionStrategy.AsynchronousDurable";
        }
    }
}
